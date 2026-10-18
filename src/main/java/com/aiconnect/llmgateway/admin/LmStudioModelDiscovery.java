package com.aiconnect.llmgateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiconnect.llmgateway.domain.RuntimeType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class LmStudioModelDiscovery {
    private final ObjectMapper objectMapper;

    public LmStudioModelDiscovery(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<DiscoveredRuntimeModel> discover(JsonNode body) {
        return discover(body, null);
    }

    public boolean hasPreparingModel(List<DiscoveredRuntimeModel> models) {
        return models.stream().anyMatch(model -> {
            try {
                String state = objectMapper.readTree(model.metadataJson()).path("runtimeState").asText();
                return Set.of("LOADING", "DOWNLOADING", "UNLOADING").contains(state);
            } catch (Exception ignored) { return false; }
        });
    }

    public boolean hasUnknownLoadState(JsonNode inventory, List<DiscoveredRuntimeModel> models) {
        if (inventory != null && inventory.has("load_state_known") && !inventory.path("load_state_known").asBoolean()) return true;
        return models.stream().anyMatch(model -> {
            try {
                return "UNKNOWN".equals(objectMapper.readTree(model.metadataJson()).path("runtimeState").asText());
            } catch (Exception ignored) { return true; }
        });
    }

    public List<DiscoveredRuntimeModel> discover(JsonNode body, RuntimeType runtimeType) {
        if (body == null) return List.of();
        if (runtimeType == RuntimeType.LLAMA_CPP) return discoverLlamaCpp(body);
        if (runtimeType == RuntimeType.LM_STUDIO && body.path("models").isArray()) {
            return discoverNativeV1(body.path("models"), body.path("lm_studio_legacy_models"));
        }
        // Some OpenAI-compatible servers (including llama.cpp) return both a
        // provider-specific "models" array and the canonical OpenAI "data"
        // array. Prefer the canonical list when it contains usable model IDs;
        // the provider-specific entries may not use LM Studio key fields.
        if (body.path("data").isArray()) {
            // LM Studio's OpenAI list may include downloaded models under JIT.
            // Membership alone is not evidence that an instance is loaded.
            List<DiscoveredRuntimeModel> compatible = discoverCompatibleList(body.path("data"), runtimeType != RuntimeType.LM_STUDIO, runtimeType);
            if (!compatible.isEmpty() || !body.path("models").isArray()) return compatible;
        }
        if (body.path("models").isArray()) return discoverNativeV1(body.path("models"), body.path("lm_studio_legacy_models"));
        return List.of();
    }

    private List<DiscoveredRuntimeModel> discoverLlamaCpp(JsonNode body) {
        JsonNode data = body.path("data");
        JsonNode catalog = body.path("models");
        List<DiscoveredRuntimeModel> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // llama.cpp router mode exposes its configured inventory at GET /models;
        // /v1/models is the separate OpenAI-compatible view. Explicit router
        // lifecycle state wins, while membership in /v1/models is only a fallback
        // signal when the router omitted state for that item.
        if (catalog.isArray()) {
            for (JsonNode model : catalog) {
                String catalogId = firstNonBlank(text(model, "name", null), text(model, "id", null), text(model, "model", null));
                if (catalogId == null || catalogId.isBlank()) continue;
                JsonNode apiModel = matchingCatalogEntry(data, model, catalogId);
                String id = apiModel == null ? catalogId : text(apiModel, "id", catalogId);
                JsonNode merged = mergeModelMetadata(apiModel, model);
                result.add(compatibleModel(merged, id, apiModel != null, RuntimeType.LLAMA_CPP));
                seen.add(identity(id));
                seen.add(identity(catalogId));
                seen.add(identity(text(model, "model", null)));
            }
        }

        // Standalone llama.cpp exposes its active model only through /v1/models.
        // Any entries not represented in the router catalog are therefore served.
        if (data.isArray()) {
            for (JsonNode apiModel : data) {
                String id = text(apiModel, "id", null);
                if (id == null || id.isBlank() || seen.contains(identity(id))) continue;
                result.add(compatibleModel(apiModel, id, true, RuntimeType.LLAMA_CPP));
                seen.add(identity(id));
            }
        }
        return result;
    }

    private JsonNode matchingCatalogEntry(JsonNode catalog, JsonNode apiModel, String apiId) {
        if (!catalog.isArray()) return null;
        for (JsonNode candidate : catalog) {
            String name = text(candidate, "name", null);
            String id = text(candidate, "id", null);
            String model = text(candidate, "model", null);
            if (sameModel(apiId, name) || sameModel(apiId, id) || sameModel(apiId, model)
                    || sameModel(text(apiModel, "name", null), name)
                    || sameModel(text(apiModel, "model", null), model)) return candidate;
        }
        return null;
    }

    private boolean sameModel(String first, String second) {
        return first != null && second != null && !identity(first).isBlank() && identity(first).equals(identity(second));
    }

    private String identity(String value) {
        return canonicalLlamaCppIdentity(value);
    }

    /** Canonical identity for reconciling old llama.cpp path IDs with router aliases. */
    public static String canonicalLlamaCppIdentity(String value) {
        if (value == null) return "";
        String normalized = value.trim().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        if (normalized.endsWith(".gguf")) normalized = normalized.substring(0, normalized.length() - 5);
        return normalized;
    }

    private JsonNode mergeModelMetadata(JsonNode apiModel, JsonNode catalogModel) {
        if (apiModel == null || !apiModel.isObject()) return catalogModel;
        if (catalogModel == null || !catalogModel.isObject()) return apiModel;
        ObjectNode merged = ((ObjectNode) catalogModel).deepCopy();
        apiModel.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        // The router inventory owns lifecycle state; /v1/models only supplements
        // the public API identifier and loaded-model metadata.
        if (catalogModel.has("status")) merged.set("status", catalogModel.path("status"));
        if (catalogModel.has("state")) merged.set("state", catalogModel.path("state"));
        return merged;
    }

    private DiscoveredRuntimeModel compatibleModel(JsonNode model, String id, boolean missingStateMeansLoaded) {
        return compatibleModel(model, id, missingStateMeansLoaded, null);
    }

    private DiscoveredRuntimeModel compatibleModel(JsonNode model, String id, boolean missingStateMeansLoaded,
                                                     RuntimeType runtimeType) {
        String state = loadState(model);
        if (state == null && missingStateMeansLoaded) state = "loaded";
        String runtimeState = normalizedRuntimeState(state);
        boolean loaded = "LOADED".equals(runtimeState);
        Set<String> capabilities = compatibleCapabilities(model);
        JsonNode metadata = model.path("meta");
        String family = text(model, "arch", firstNonBlank(metadata.path("families")));
        String quantization = text(model, "quantization", text(metadata, "ftype", null));
        ObjectNode settings = runtimeSettings(model, runtimeType);
        Integer contextLength = positiveInt(settings.path("contextLength"),
                positiveInt(model.path("context_length"), positiveInt(model.path("max_context_length"),
                        positiveInt(metadata.path("n_ctx"), null))));
        if (runtimeType == RuntimeType.LM_STUDIO) {
            Integer maximum = positiveInt(model.path("max_context_length"), null);
            if (maximum != null) settings.put("maxContextLength", maximum);
            Integer loadedContext = positiveInt(model.path("loaded_context_length"), null);
            if (loaded && loadedContext != null) {
                contextLength = loadedContext;
                settings.put("contextLength", loadedContext);
                settings.put("contextLengthSource", "LM_STUDIO_V0");
            } else if (loaded) {
                contextLength = positiveInt(settings.path("contextLength"), null);
            }
        }
        String displayName = text(model, "display_name", id);
        ObjectNode enriched = enrichMetadata(model, runtimeState, settings);
        if (runtimeType == RuntimeType.LM_STUDIO && model.hasNonNull("state") && !enriched.has("loadStateEvidence")) {
            enriched.set("loadStateEvidence", loadStateEvidence("UNKNOWN", runtimeState));
        }
        return new DiscoveredRuntimeModel(id, id, displayName, family, quantization, contextLength,
                loaded, positiveInt(settings.path("parallelSlots"), 1), json(capabilities),
                json(enriched));
    }

    private String loadState(JsonNode model) {
        JsonNode status = model.path("status");
        if (status.path("failed").asBoolean(false)) return "error";
        String state = text(status, "value", null);
        if (state == null) state = text(model, "state", null);
        if (state != null && !state.isBlank()) return state;
        JsonNode instances = model.path("loaded_instances");
        if (instances.isArray()) return instances.isEmpty() ? "unloaded" : "loaded";
        return null;
    }

    private List<DiscoveredRuntimeModel> discoverNativeV1(JsonNode models, JsonNode legacyModels) {
        List<DiscoveredRuntimeModel> discovered = new ArrayList<>();
        Set<String> matchedLegacyIds = new LinkedHashSet<>();
        for (JsonNode model : models) {
            String key = text(model, "key", null);
            if (key == null || key.isBlank()) continue;
            // A legacy catalog key must not become a duplicate active entry
            // when v1 already provides this model's custom instance IDs.
            rememberLegacyId(matchingLegacyModel(legacyModels, key), matchedLegacyIds);
            JsonNode instances = model.path("loaded_instances");
            if (!instances.isArray() || instances.isEmpty()) {
                JsonNode legacy = matchingLegacyModel(legacyModels, key);
                discovered.add(nativeModel(model, null, legacy));
                continue;
            }
            for (JsonNode instance : instances) {
                String instanceId = text(instance, "id", key);
                JsonNode legacy = matchingLegacyModel(legacyModels, instanceId);
                rememberLegacyId(legacy, matchedLegacyIds);
                discovered.add(nativeModel(model, instance, legacy));
            }
        }
        // A custom active API identifier can be absent from the v1 catalog.
        // Retain only explicitly active/preparing v0 entries in that case; do
        // not turn the legacy downloaded catalog into duplicate candidates.
        if (legacyModels.isArray()) for (JsonNode legacy : legacyModels) {
            String id = text(legacy, "id", null);
            String state = normalizedRuntimeState(loadState(legacy));
            if (id == null || id.isBlank() || matchedLegacyIds.contains(id)
                    || !Set.of("LOADED", "LOADING", "UNLOADING", "DOWNLOADING").contains(state)) continue;
            ObjectNode supplemental = ((ObjectNode) legacy).deepCopy();
            supplemental.set("loadStateEvidence", loadStateEvidence("UNKNOWN", state));
            discovered.add(compatibleModel(supplemental, id, false, RuntimeType.LM_STUDIO));
        }
        return discovered;
    }

    private JsonNode matchingLegacyModel(JsonNode models, String id) {
        if (!models.isArray()) return null;
        // Only exact documented identifiers are safe. Basename/quantization
        // stripping could accidentally associate another model or Q4/Q8 file.
        for (JsonNode model : models) if (id.equals(text(model, "id", null))) return model;
        return null;
    }

    private void rememberLegacyId(JsonNode legacy, Set<String> ids) {
        if (legacy != null) ids.add(text(legacy, "id", ""));
    }

    private DiscoveredRuntimeModel nativeModel(JsonNode model, JsonNode instance, JsonNode legacy) {
        String key = text(model, "key", "");
        String nativeState = instance == null ? normalizedRuntimeState(loadState(model)) : "LOADED";
        String legacyState = legacy == null ? "UNKNOWN" : normalizedRuntimeState(loadState(legacy));
        // v0 is queried after v1 and explicitly reports the current lifecycle.
        // Unknown/absent legacy state never overrides native loaded evidence.
        String state = legacyState.equals("UNKNOWN") ? nativeState : legacyState;
        boolean loaded = state.equals("LOADED");
        // Model-level defaults are not proof of the active instance's settings.
        JsonNode config = instance == null ? objectMapper.createObjectNode() : instance.path("config");
        ObjectNode settings = runtimeSettings(loaded ? config : objectMapper.createObjectNode(), RuntimeType.LM_STUDIO);
        Integer maximum = positiveInt(model.path("max_context_length"), null);
        if (maximum != null) settings.put("maxContextLength", maximum);
        Integer context = loaded ? positiveInt(config.path("context_length"), null) : maximum;
        Integer legacyContext = legacy == null ? null : positiveInt(legacy.path("loaded_context_length"), null);
        if (loaded && legacyContext != null) {
            context = legacyContext;
            settings.put("contextLength", context);
            settings.put("contextLengthSource", "LM_STUDIO_V0");
        }
        String id = instance == null ? (loaded && legacy != null ? text(legacy, "id", key) : key) : text(instance, "id", key);
        ObjectNode metadata = enrichMetadata(model, state, settings);
        metadata.set("loadStateEvidence", loadStateEvidence(nativeState, legacyState));
        String quantization = text(model.path("quantization"), "name", text(model, "selected_variant", null));
        Set<String> capabilities = nativeCapabilities(model);
        if (legacy != null) {
            // Preserve the native feature/configuration information while
            // supplementing explicit legacy VLM and tool-use evidence.
            compatibleCapabilities(legacy).stream()
                    .filter(feature -> Set.of("VISION", "TOOL_CALLING", "REASONING").contains(feature))
                    .forEach(capabilities::add);
            if (loaded) quantization = text(legacy, "quantization", quantization);
        }
        return new DiscoveredRuntimeModel(id, key, text(model, "display_name", key), text(model, "architecture", null),
                quantization, context, loaded, loaded ? positiveInt(config.path("parallel"), 1) : 1,
                json(capabilities), json(metadata));
    }

    private ObjectNode loadStateEvidence(String nativeState, String legacyState) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("source", legacyState.equals("UNKNOWN") ? "LM_STUDIO_V1" : "LM_STUDIO_V0");
        evidence.put("nativeState", nativeState);
        evidence.put("legacyState", legacyState);
        evidence.put("conflict", !nativeState.equals("UNKNOWN") && !legacyState.equals("UNKNOWN") && !nativeState.equals(legacyState));
        return evidence;
    }

    private List<DiscoveredRuntimeModel> discoverCompatibleList(JsonNode data, boolean missingStateMeansLoaded) {
        return discoverCompatibleList(data, missingStateMeansLoaded, null);
    }

    private List<DiscoveredRuntimeModel> discoverCompatibleList(JsonNode data, boolean missingStateMeansLoaded,
                                                                 RuntimeType runtimeType) {
        List<DiscoveredRuntimeModel> discovered = new ArrayList<>();
        for (JsonNode model : data) {
            String id = text(model, "id", null);
            if (id == null || id.isBlank()) continue;
            discovered.add(compatibleModel(model, id, missingStateMeansLoaded, runtimeType));
        }
        return discovered;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private String firstNonBlank(JsonNode values) {
        if (!values.isArray()) return null;
        for (JsonNode value : values) {
            String text = value.asText("").trim();
            if (!text.isBlank()) return text;
        }
        return null;
    }
    private Set<String> nativeCapabilities(JsonNode model) {
        Set<String> result = new LinkedHashSet<>();
        if ("embedding".equalsIgnoreCase(text(model, "type", ""))) {
            result.add("EMBEDDING");
            return result;
        }
        result.add("CHAT_COMPLETION");
        result.add("STREAMING");
        JsonNode capabilities = model.path("capabilities");
        if (capabilities.path("vision").asBoolean(false)) result.add("VISION");
        if (capabilities.path("trained_for_tool_use").asBoolean(false)) result.add("TOOL_CALLING");
        if (capabilities.hasNonNull("reasoning")) result.add("REASONING");
        return result;
    }

    private Set<String> compatibleCapabilities(JsonNode model) {
        Set<String> result = new LinkedHashSet<>();
        String type = text(model, "type", "llm");
        if (type.toLowerCase().contains("embed")) result.add("EMBEDDING");
        else {
            result.add("CHAT_COMPLETION");
            result.add("STREAMING");
        }
        if (type.equalsIgnoreCase("vlm")) result.add("VISION");
        JsonNode values = model.path("capabilities");
        if (values.isArray()) {
            for (JsonNode value : values) {
                String capability = value.asText("");
                if (capability.equalsIgnoreCase("tool_use") || capability.equalsIgnoreCase("tool_calling")) result.add("TOOL_CALLING");
                if (capability.equalsIgnoreCase("vision")) result.add("VISION");
                if (capability.equalsIgnoreCase("reasoning")) result.add("REASONING");
            }
        }
        return result;
    }

    private String text(JsonNode node, String field, String fallback) {
        return node.hasNonNull(field) ? node.path(field).asText() : fallback;
    }

    private Integer positiveInt(JsonNode node, Integer fallback) {
        int value = node.asInt(0);
        if (value > 0) return value;
        return fallback;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { return value instanceof Set<?> ? "[]" : null; }
    }

    private String normalizedRuntimeState(String state) {
        if (state == null || state.isBlank()) return "UNKNOWN";
        return switch (state.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "loaded", "ready", "running" -> "LOADED";
            case "sleep", "sleeping", "suspended" -> "SLEEPING";
            case "loading", "starting", "warming" -> "LOADING";
            case "downloading", "download" -> "DOWNLOADING";
            case "unloading", "stopping" -> "UNLOADING";
              case "unloaded", "not_loaded", "not-loaded", "downloaded", "stopped" -> "UNLOADED";
            case "error", "failed", "failure", "unhealthy", "unavailable", "not_available",
                    "not available", "not_found", "not-found", "not found", "missing", "invalid" -> "ERROR";
            default -> "UNKNOWN";
        };
    }

    private ObjectNode runtimeSettings(JsonNode source, RuntimeType runtimeType) {
        ObjectNode settings = objectMapper.createObjectNode();
        if (runtimeType == RuntimeType.LLAMA_CPP) {
            settings.put("source", "LLAMA_CPP_ROUTER_API");
            copyLlamaCppArgs(source.path("status").path("args"), settings);
            JsonNode props = source.path("props");
            copySafeScalars(props, settings);
            JsonNode effectiveContext = props.path("default_generation_settings").path("n_ctx");
            if (effectiveContext.canConvertToInt() && effectiveContext.asInt() > 0) {
                settings.put("contextLength", effectiveContext.asInt());
            }
            JsonNode totalSlots = props.path("total_slots");
            if (totalSlots.canConvertToInt() && totalSlots.asInt() > 0) settings.put("parallelSlots", totalSlots.asInt());
            String path = firstNonBlank(text(source, "model", null), text(source, "path", null), text(source, "name", null));
            if (path != null && path.contains("/")) settings.put("modelPath", path);
        } else if (runtimeType == RuntimeType.OLLAMA) {
            settings.put("source", "OLLAMA_API");
            copySafeScalars(source.path("runtimeSettings"), settings);
        } else if (runtimeType == RuntimeType.OPENAI_COMPATIBLE) {
            settings.put("source", "OPENAI_COMPATIBLE_API");
            copySafeScalars(source, settings);
        } else {
            settings.put("source", "LM_STUDIO_API");
            copySafeScalars(source.has("config") ? source.path("config") : source, settings);
        }
        normalizeSetting(settings, "contextLength", "context_length", "n_ctx", "ctx-size", "ctx_size");
        normalizeSetting(settings, "parallelSlots", "parallel", "n_parallel", "parallel_slots", "total_slots");
        normalizeSetting(settings, "evalBatchSize", "eval_batch_size", "n_batch", "batch_size");
        normalizeSetting(settings, "physicalBatchSize", "physical_batch_size", "n_ubatch", "ubatch_size");
        normalizeSetting(settings, "cpuThreads", "cpu_threads", "threads", "n_threads");
        normalizeSetting(settings, "flashAttention", "flash_attention", "flash_attn");
        normalizeSetting(settings, "offloadKvCacheToGpu", "offload_kv_cache_to_gpu");
        normalizeSetting(settings, "gpuLayers", "n_gpu_layers", "gpu_offload", "gpu_layers");
        normalizeSetting(settings, "kvCacheTypeK", "cache_type_k", "k_cache_quantization");
        normalizeSetting(settings, "kvCacheTypeV", "cache_type_v", "v_cache_quantization");
        normalizeSetting(settings, "modelPath", "model_path", "model");
        normalizeSetting(settings, "vramBytes", "size_vram", "vram_bytes");
        normalizeSetting(settings, "sizeBytes", "size", "size_bytes");
        normalizeSetting(settings, "expiresAt", "expires_at");
        normalizeSetting(settings, "maxConcurrency", "parallel", "max_concurrency", "max_concurrent_predictions");
        return settings;
    }

    private void copySafeScalars(JsonNode source, ObjectNode target) {
        if (source == null || source.isMissingNode() || !source.isObject()) return;
        source.fields().forEachRemaining(entry -> {
            if (sensitiveName(entry.getKey())) return;
            if (entry.getValue().isValueNode()) target.set(entry.getKey(), entry.getValue().deepCopy());
            else if (entry.getValue().isObject()) copySafeScalars(entry.getValue(), target);
        });
    }

    private void copyLlamaCppArgs(JsonNode args, ObjectNode target) {
        if (args == null || args.isMissingNode() || args.isNull()) return;
        if (args.isObject()) {
            args.fields().forEachRemaining(entry -> {
                String key = canonicalLlamaFlag(entry.getKey());
                if (key != null && !sensitiveName(entry.getKey()) && entry.getValue().isValueNode()) {
                    JsonNode value = entry.getValue();
                    if (isNegativeKvOffloadFlag(entry.getKey())) {
                        value = objectMapper.getNodeFactory().booleanNode(false);
                    }
                    target.set(key, value.deepCopy());
                    if ("kv_offload".equals(key)) target.set("offloadKvCacheToGpu", value.deepCopy());
                }
            });
            return;
        }
        if (!args.isArray()) return;
        for (int index = 0; index < args.size(); index++) {
            String arg = args.path(index).asText("").trim();
            if (!arg.startsWith("-") || arg.equals("-")) continue;
            int prefixLength = arg.startsWith("--") ? 2 : 1;
            String rawFlag = arg.substring(prefixLength);
            String rawValue = null;
            int equals = rawFlag.indexOf('=');
            if (equals >= 0) {
                rawValue = rawFlag.substring(equals + 1);
                rawFlag = rawFlag.substring(0, equals);
            }
            if (sensitiveName(rawFlag)) {
                if (equals < 0 && index + 1 < args.size() && !args.path(index + 1).asText("").startsWith("-")) index++;
                continue;
            }
            String key = canonicalLlamaFlag(rawFlag);
            if (key == null) continue;
            if (rawValue == null && index + 1 < args.size() && !args.path(index + 1).asText("").startsWith("-")) {
                rawValue = args.path(++index).asText();
            }
            if ("kv_offload".equals(key) && isNegativeKvOffloadFlag(rawFlag)) {
                target.put(key, false);
                target.put("offloadKvCacheToGpu", false);
            } else if (rawValue == null) {
                target.put(key, true);
                if ("kv_offload".equals(key)) target.put("offloadKvCacheToGpu", true);
            } else {
                JsonNode value = scalar(rawValue);
                target.set(key, value);
                if ("kv_offload".equals(key)) target.set("offloadKvCacheToGpu", value.deepCopy());
            }
        }
    }

    private String canonicalLlamaFlag(String name) {
        if (name == null) return null;
        String flag = name.trim().replaceFirst("^-+", "").toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        return switch (flag) {
            case "c", "ctx", "ctx-size", "context-length", "n-ctx" -> "context_length";
            case "np", "parallel", "n-parallel", "parallel-slots" -> "parallel_slots";
            case "b", "batch", "batch-size", "eval-batch-size", "n-batch" -> "eval_batch_size";
            case "ub", "ubatch-size", "physical-batch-size" -> "physical_batch_size";
            case "t", "threads", "cpu-threads" -> "cpu_threads";
            case "ngl", "n-gpu-layers", "gpu-layers", "gpu-offload" -> "n_gpu_layers";
            case "ctk", "cache-type-k" -> "cache_type_k";
            case "ctv", "cache-type-v" -> "cache_type_v";
            case "fa", "flash-attn", "flash-attention" -> "flash_attention";
            case "kvo", "kv-offload", "nkvo", "no-kv-offload" -> "kv_offload";
            default -> null;
        };
    }

    private boolean isNegativeKvOffloadFlag(String name) {
        if (name == null) return false;
        String flag = name.trim().replaceFirst("^-+", "").toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        return flag.equals("no-kv-offload") || flag.equals("nkvo");
    }

    private boolean sensitiveName(String value) {
        if (value == null) return false;
        String name = value.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return name.matches(".*(^|_)(api_key|password|passwd|secret|credential|authorization|bearer|access_token|refresh_token|hf_token|token)($|_).*" );
    }

    private JsonNode scalar(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return objectMapper.getNodeFactory().booleanNode(Boolean.parseBoolean(value));
        }
        try { return objectMapper.getNodeFactory().numberNode(Long.parseLong(value)); }
        catch (NumberFormatException ignored) { return objectMapper.getNodeFactory().textNode(value); }
    }

    private void normalizeSetting(ObjectNode settings, String normalizedName, String... aliases) {
        if (settings.has(normalizedName)) return;
        for (String alias : aliases) {
            JsonNode value = settings.get(alias);
            if (value != null && value.isValueNode()) {
                settings.set(normalizedName, value.deepCopy());
                return;
            }
        }
    }

    private ObjectNode enrichMetadata(JsonNode raw, String state, ObjectNode runtimeSettings) {
        ObjectNode metadata = raw != null && raw.isObject() ? (ObjectNode) sanitize(raw) : objectMapper.createObjectNode();
        metadata.put("runtimeState", state);
        metadata.set("runtimeSettings", runtimeSettings == null ? objectMapper.createObjectNode() : runtimeSettings);
        return metadata;
    }

    private JsonNode sanitize(JsonNode raw) {
        if (raw == null || raw.isNull()) return objectMapper.nullNode();
        if (raw.isObject()) {
            ObjectNode clean = objectMapper.createObjectNode();
            raw.fields().forEachRemaining(entry -> {
                if (sensitiveName(entry.getKey())) return;
                if ("args".equals(entry.getKey()) && entry.getValue().isArray()) {
                    clean.set(entry.getKey(), sanitizeArgs(entry.getValue()));
                } else {
                    clean.set(entry.getKey(), sanitize(entry.getValue()));
                }
            });
            return clean;
        }
        if (raw.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode clean = objectMapper.createArrayNode();
            raw.forEach(value -> clean.add(sanitize(value)));
            return clean;
        }
        return raw.deepCopy();
    }

    private JsonNode sanitizeArgs(JsonNode args) {
        com.fasterxml.jackson.databind.node.ArrayNode clean = objectMapper.createArrayNode();
        for (int index = 0; index < args.size(); index++) {
            String arg = args.path(index).asText("");
            String option = arg.replaceFirst("^-+", "");
            int equals = option.indexOf('=');
            String name = equals >= 0 ? option.substring(0, equals) : option;
            if (sensitiveName(name)) {
                if (equals < 0 && index + 1 < args.size() && !args.path(index + 1).asText("").startsWith("-")) index++;
                continue;
            }
            clean.add(sanitize(args.path(index)));
        }
        return clean;
    }
}
