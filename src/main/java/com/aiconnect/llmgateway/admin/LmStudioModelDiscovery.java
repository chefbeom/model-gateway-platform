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

    public List<DiscoveredRuntimeModel> discover(JsonNode body, RuntimeType runtimeType) {
        if (body == null) return List.of();
        if (runtimeType == RuntimeType.LLAMA_CPP) return discoverLlamaCpp(body);
        // Some OpenAI-compatible servers (including llama.cpp) return both a
        // provider-specific "models" array and the canonical OpenAI "data"
        // array. Prefer the canonical list when it contains usable model IDs;
        // the provider-specific entries may not use LM Studio key fields.
        if (body.path("data").isArray()) {
            List<DiscoveredRuntimeModel> compatible = discoverCompatibleList(body.path("data"), true);
            if (!compatible.isEmpty() || !body.path("models").isArray()) return compatible;
        }
        if (body.path("models").isArray()) return discoverNativeV1(body.path("models"));
        return List.of();
    }

    private List<DiscoveredRuntimeModel> discoverLlamaCpp(JsonNode body) {
        JsonNode data = body.path("data");
        JsonNode catalog = body.path("models");
        List<DiscoveredRuntimeModel> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // In router mode, /v1/models is the OpenAI-compatible catalog and carries
        // status.value. Prefer those public model IDs, enriching them from the
        // router's catalog (whose model field may be an absolute GGUF path).
        if (data.isArray()) {
            for (JsonNode apiModel : data) {
                String id = text(apiModel, "id", null);
                if (id == null || id.isBlank()) continue;
                if (seen.contains(identity(id))) continue;
                JsonNode catalogModel = matchingCatalogEntry(catalog, apiModel, id);
                JsonNode merged = mergeModelMetadata(apiModel, catalogModel);
                result.add(compatibleModel(merged, id, false));
                seen.add(identity(id));
                if (catalogModel != null) {
                    seen.add(identity(text(catalogModel, "name", text(catalogModel, "model", text(catalogModel, "id", null)))));
                    seen.add(identity(text(catalogModel, "model", null)));
                }
            }
        }

        // Some llama.cpp builds expose downloaded candidates only in `models`.
        // Keep them visible, but never infer LOADED from presence in the catalog.
        if (catalog.isArray()) {
            for (JsonNode model : catalog) {
                String id = firstNonBlank(text(model, "name", null), text(model, "id", null), text(model, "model", null));
                if (id == null || id.isBlank() || seen.contains(identity(id))) continue;
                boolean alreadyAdded = result.stream().anyMatch(item -> identity(item.providerModelId()).equals(identity(id)));
                if (alreadyAdded) continue;
                result.add(compatibleModel(model, id, false));
                seen.add(identity(id));
            }
        }

        if (!result.isEmpty()) return result;
        // Non-router/single-model servers may expose only the ordinary data list.
        // Without an explicit status, the response is not proof that the model is loaded.
        return data.isArray() ? discoverCompatibleList(data, false) : List.of();
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
        if (value == null) return "";
        String normalized = value.trim().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        if (normalized.endsWith(".gguf")) normalized = normalized.substring(0, normalized.length() - 5);
        return normalized;
    }

    private JsonNode mergeModelMetadata(JsonNode apiModel, JsonNode catalogModel) {
        if (catalogModel == null || !catalogModel.isObject()) return apiModel;
        ObjectNode merged = ((ObjectNode) catalogModel).deepCopy();
        apiModel.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        if (!apiModel.has("status") && catalogModel.has("status")) merged.set("status", catalogModel.path("status"));
        return merged;
    }

    private DiscoveredRuntimeModel compatibleModel(JsonNode model, String id, boolean missingStateMeansLoaded) {
        String state = loadState(model);
        boolean loaded = "loaded".equalsIgnoreCase(state)
                || (state == null && missingStateMeansLoaded);
        Set<String> capabilities = compatibleCapabilities(model);
        JsonNode metadata = model.path("meta");
        String family = text(model, "arch", firstNonBlank(metadata.path("families")));
        String quantization = text(model, "quantization", text(metadata, "ftype", null));
        Integer contextLength = positiveInt(model.path("max_context_length"),
                positiveInt(model.path("context_length"), positiveInt(metadata.path("n_ctx"), null)));
        String displayName = text(model, "display_name", id);
        return new DiscoveredRuntimeModel(id, id, displayName, family, quantization, contextLength,
                loaded, 1, json(capabilities), json(model));
    }

    private String loadState(JsonNode model) {
        String state = text(model.path("status"), "value", null);
        if (state == null) state = text(model, "state", null);
        if (state != null && !state.isBlank()) return state;
        JsonNode instances = model.path("loaded_instances");
        if (instances.isArray()) return instances.isEmpty() ? "unloaded" : "loaded";
        return null;
    }

    private List<DiscoveredRuntimeModel> discoverNativeV1(JsonNode models) {
        List<DiscoveredRuntimeModel> discovered = new ArrayList<>();
        for (JsonNode model : models) {
            String key = text(model, "key", null);
            if (key == null || key.isBlank()) continue;
            String displayName = text(model, "display_name", key);
            String family = text(model, "architecture", null);
            String quantization = text(model.path("quantization"), "name", text(model, "selected_variant", null));
            Set<String> capabilities = nativeCapabilities(model);
            JsonNode instances = model.path("loaded_instances");
            if (!instances.isArray() || instances.isEmpty()) {
                discovered.add(new DiscoveredRuntimeModel(key, key, displayName, family, quantization,
                        positiveInt(model.path("max_context_length"), null), false, 1,
                        json(capabilities), json(model)));
                continue;
            }
            for (JsonNode instance : instances) {
                String instanceId = text(instance, "id", key);
                JsonNode config = instance.path("config");
                discovered.add(new DiscoveredRuntimeModel(instanceId, key, displayName, family, quantization,
                        positiveInt(config.path("context_length"), positiveInt(model.path("max_context_length"), null)),
                        true, positiveInt(config.path("parallel"), 1), json(capabilities), json(model)));
            }
        }
        return discovered;
    }

    private List<DiscoveredRuntimeModel> discoverCompatibleList(JsonNode data, boolean missingStateMeansLoaded) {
        List<DiscoveredRuntimeModel> discovered = new ArrayList<>();
        for (JsonNode model : data) {
            String id = text(model, "id", null);
            if (id == null || id.isBlank()) continue;
            discovered.add(compatibleModel(model, id, missingStateMeansLoaded));
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
}
