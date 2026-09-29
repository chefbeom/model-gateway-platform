package com.aiconnect.llmgateway.modelops;

import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.routing.ActiveRequestRegistry;
import com.aiconnect.llmgateway.runtime.InferenceRuntimeClient;
import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RuntimeModelOperationService {
    private final RuntimeEndpointRepository endpoints;
    private final ModelDeploymentRepository deployments;
    private final RuntimeModelProfileRepository profiles;
    private final RuntimeModelOperationRepository operations;
    private final LmStudioModelManagementClient models;
    private final ControlPlaneService controlPlane;
    private final InferenceRuntimeClient inference;
    private final ActiveRequestRegistry active;
    private final ObjectMapper mapper;

    public RuntimeModelOperationService(RuntimeEndpointRepository endpoints, ModelDeploymentRepository deployments,
                                        RuntimeModelProfileRepository profiles, RuntimeModelOperationRepository operations,
                                        LmStudioModelManagementClient models, ControlPlaneService controlPlane,
                                        InferenceRuntimeClient inference, ActiveRequestRegistry active, ObjectMapper mapper) {
        this.endpoints = endpoints;
        this.deployments = deployments;
        this.profiles = profiles;
        this.operations = operations;
        this.models = models;
        this.controlPlane = controlPlane;
        this.inference = inference;
        this.active = active;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PreflightResult preflight(UUID endpointId, LoadCommand command) {
        RuntimeEndpoint endpoint = endpoint(endpointId);
        requireNativeManagement(endpoint);
        RuntimeResult result = models.list(endpoint);
        if (!result.isSuccessful()) throw rejected("MODEL_LIST_FAILED", runtimeLabel(endpoint) + " rejected the model list request.");
        JsonNode model = findModelInBody(endpoint, result.body(), command.modelKey(), command.variantKey());
        if (model == null) throw rejected("MODEL_NOT_AVAILABLE", "The requested model is not downloaded on this runtime.");

        if (isLlamaCpp(endpoint)) {
            List<String> warnings = new ArrayList<>();
            if (hasLlamaUnsupportedOptions(command)) warnings.add("llama.cpp router model loading accepts the model ID only; LM Studio-specific loading options are not sent.");
            int maximum = model.path("meta").path("n_ctx_train").asInt(model.path("max_context_length").asInt(0));
            int context = command.contextLength() == null ? maximum : command.contextLength();
            return new PreflightResult(command.modelKey(), text(model, "display_name", command.modelKey()),
                    model.path("size_bytes").asLong(0), 0, maximum, context, true, warnings,
                    "loaded".equalsIgnoreCase(text(model.path("status"), "value", "")), List.of(),
                    null, null, true, true, List.of("model"));
        }

        long size = model.path("size_bytes").asLong(0);
        int maximum = model.path("max_context_length").asInt(0);
        int context = command.contextLength() == null ? maximum : command.contextLength();
        List<String> variants = strings(model.path("variants"));
        String activeVariant = nonBlank(text(model, "selected_variant", null));
        String requestedVariant = nonBlank(command.variantKey()) == null ? activeVariant : command.variantKey();
        boolean variantAvailable = requestedVariant == null || variants.isEmpty() || variants.contains(requestedVariant);
        // LM Studio exposes variants in the model list, but the documented native load endpoint
        // currently loads the model's selected variant and does not accept a variant selector.
        boolean variantSelectionSupported = variants.size() <= 1 || requestedVariant == null || requestedVariant.equals(activeVariant);
        List<String> warnings = new ArrayList<>();
        if (maximum > 0 && context > maximum) warnings.add("Requested context length exceeds the model maximum.");
        if (!variantAvailable) warnings.add("The requested model variant is not available on this runtime.");
        else if (!variantSelectionSupported) warnings.add("LM Studio native REST cannot switch Q4/Q8 variants remotely. Select the desired variant in LM Studio, synchronize again, and retry.");
        if (command.physicalBatchSize() != null) warnings.add("Physical Batch Size is saved in the profile but is not part of the documented native REST load request.");
        if (command.parallel() != null) warnings.add("Max Concurrent Predictions is reported by LM Studio but is not part of the documented native REST load request.");
        if (nonBlank(command.apiIdentifier()) != null) warnings.add("API Identifier is saved in the profile but is not part of the documented native REST load request.");
        if (command.gpuOffloadLayers() != null || nonBlank(command.gpuOffloadMode()) != null || command.gpuOffloadRatio() != null)
            warnings.add("GPU offload settings require the LM Studio CLI/SDK or a Node Agent; native REST will keep the runtime default.");
        if (command.cpuThreadPoolSize() != null) warnings.add("CPU Thread Pool Size requires the LM Studio SDK or a Node Agent.");
        if (command.unifiedKvCache() != null) warnings.add("Unified KV Cache requires the LM Studio SDK or a Node Agent.");
        if (command.ropeFrequencyBase() != null || command.ropeFrequencyScale() != null) warnings.add("RoPE frequency settings require the LM Studio SDK or a Node Agent.");
        if (command.keepModelInMemory() != null || command.tryMmap() != null || command.seed() != null) warnings.add("Keep-in-memory, mmap, and seed settings require the LM Studio SDK or a Node Agent.");
        if (nonBlank(command.kCacheQuantizationType()) != null || nonBlank(command.vCacheQuantizationType()) != null) warnings.add("K/V cache quantization requires the LM Studio SDK or a Node Agent.");
        if (command.autoUnloadTtlSeconds() != null) warnings.add("TTL is saved in the profile but requires the LM Studio CLI/SDK or a Node Agent to enforce.");
        long heuristic = size == 0 ? 0 : (long) (size * 1.15d) + ((long) context * 32768L);
        boolean compatible = (maximum == 0 || context <= maximum) && variantAvailable && variantSelectionSupported;
        return new PreflightResult(command.modelKey(), model.path("display_name").asText(command.modelKey()), size,
                heuristic, maximum, context, compatible, warnings, model.path("loaded_instances").size() > 0,
                variants, activeVariant, requestedVariant, variantAvailable, variantSelectionSupported,
                List.of("context_length", "eval_batch_size", "flash_attention", "num_experts", "offload_kv_cache_to_gpu"));
    }

    @Transactional
    public RuntimeModelProfile saveProfile(UUID endpointId, String name, LoadCommand command) {
        endpoint(endpointId);
        return profiles.save(new RuntimeModelProfile(endpointId, name, command.modelKey(), json(command)));
    }

    @Transactional(readOnly = true)
    public List<RuntimeModelProfile> profiles(UUID endpointId) {
        endpoint(endpointId);
        return profiles.findByRuntimeEndpointIdOrderByNameAsc(endpointId);
    }

    @Transactional(readOnly = true)
    public List<RuntimeModelOperation> operations(UUID endpointId) {
        endpoint(endpointId);
        return operations.findTop30ByRuntimeEndpointIdOrderByCreatedAtDesc(endpointId);
    }

    @Transactional
    public RuntimeModelOperation load(UUID endpointId, LoadCommand command, UUID profileId) {
        return apply(endpointId, command, profileId, "LOAD", true);
    }

    @Transactional
    public RuntimeModelOperation applyProfile(UUID endpointId, UUID profileId) {
        RuntimeModelProfile profile = profiles.findById(profileId)
                .filter(item -> endpointId.equals(item.getRuntimeEndpointId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MODEL_PROFILE_NOT_FOUND", "The model profile does not exist on this runtime."));
        try {
            return apply(endpointId, mapper.readValue(profile.getConfigJson(), LoadCommand.class), profile.getId(), "LOAD", true);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected("MODEL_PROFILE_INVALID", "The saved model profile cannot be read.");
        }
    }

    @Transactional
    public RuntimeModelOperation unload(UUID endpointId, String modelKey) {
        return apply(endpointId, new LoadCommand(modelKey, null, null, null, null, null, null, null, null, null), null, "UNLOAD", true);
    }

    @Transactional
    public RuntimeModelOperation download(UUID endpointId, String modelKey, String quantization) {
        RuntimeEndpoint endpoint = endpoint(endpointId);
        requireLmStudioManagement(endpoint);
        ObjectNode request = mapper.createObjectNode().put("model", modelKey);
        if (quantization != null && !quantization.isBlank()) request.put("quantization", quantization);
        RuntimeModelOperation operation = operations.save(new RuntimeModelOperation(endpointId, null, modelKey, "DOWNLOAD", json(request)));
        try {
            RuntimeResult result = models.download(endpoint, request);
            if (!result.isSuccessful()) operation.fail(failureMessage(endpoint, result));
            else operation.complete(json(result.body()), result.body().path("status").asText("Download requested."));
        } catch (RuntimeException exception) {
            operation.fail(exception.getMessage());
        }
        return operations.save(operation);
    }

    @Transactional(readOnly = true)
    public RuntimeResult downloadStatus(UUID endpointId, String jobId) {
        RuntimeEndpoint endpoint = endpoint(endpointId);
        requireLmStudioManagement(endpoint);
        return models.downloadStatus(endpoint, jobId);
    }

    private RuntimeModelOperation apply(UUID endpointId, LoadCommand command, UUID profileId, String type, boolean safe) {
        RuntimeEndpoint endpoint = endpoint(endpointId);
        requireNativeManagement(endpoint);
        JsonNode model = null;
        String warmupModelKey = command.modelKey();
        List<String> preflightWarnings = List.of();
        if ("LOAD".equals(type)) {
            PreflightResult check = preflight(endpointId, command);
            preflightWarnings = check.warnings();
            if (!check.compatible()) {
                String detail = check.warnings().isEmpty() ? "Review the model configuration and try again." : String.join(" ", check.warnings());
                throw rejected("MODEL_CONFIGURATION_INCOMPATIBLE", detail);
            }
            RuntimeResult listing = models.list(endpoint);
            if (!listing.isSuccessful()) throw rejected("MODEL_LIST_FAILED", runtimeLabel(endpoint) + " rejected the model list request.");
            model = findModelInBody(endpoint, listing.body(), command.modelKey(), command.variantKey());
            if (model == null) throw rejected("MODEL_NOT_AVAILABLE", "The requested model is not downloaded on this runtime.");
            warmupModelKey = text(model, "key", text(model, "id", text(model, "name", command.modelKey())));
        }
        // LM Studio unload addresses its loaded instance; llama.cpp router uses the catalog model ID.
        ObjectNode request = "LOAD".equals(type) ? request(endpoint, command, model) : unloadRequest(endpoint, command.modelKey());
        RuntimeModelOperation operation = operations.save(new RuntimeModelOperation(endpointId, profileId, command.modelKey(), type, json(request)));
        try {
            if (safe) {
                endpoint.beginDraining();
                endpoints.save(endpoint);
                int activeCount = deployments.findByRuntimeEndpointId(endpointId).stream().mapToInt(item -> active.count(item.getId())).sum();
                if (activeCount > 0) {
                    operation.waitForDrain("Waiting for " + activeCount + " in-flight request(s) to finish. Retry the operation after drain.");
                    return operations.save(operation);
                }
            }
            RuntimeResult result = "LOAD".equals(type) ? models.load(endpoint, request) : models.unload(endpoint, request);
            if (!result.isSuccessful()) {
                operation.fail(failureMessage(endpoint, result));
                if (safe) releaseDrain(endpoint, true);
                return operations.save(operation);
            }
            // The runtime response may assign a new loaded instance ID. Pass the
            // selected model key so stale service targets can follow this explicit
            // model operation when the replacement is unambiguous.
            controlPlane.syncModels(endpointId, warmupModelKey);
            String recoveryMessage = recover(endpoint, warmupModelKey, "LOAD".equals(type));
            operation.complete(json(result.body()), withWarnings(recoveryMessage, preflightWarnings));
        } catch (RuntimeException exception) {
            operation.fail(exception.getMessage());
            if (safe) releaseDrain(endpoint, false);
        }
        return operations.save(operation);
    }

    private void releaseDrain(RuntimeEndpoint endpoint, boolean runtimeResponded) {
        if (endpoint.getHealthStatus() == com.aiconnect.llmgateway.domain.HealthStatus.DRAINING) endpoint.beginRecovery();
        if (endpoint.getHealthStatus() == com.aiconnect.llmgateway.domain.HealthStatus.RECOVERING) {
            if (runtimeResponded) endpoint.completeRecovery();
            else endpoint.failRecovery();
        }
        endpoints.save(endpoint);
    }

    private String recover(RuntimeEndpoint endpoint, String modelKey, boolean loading) {
        endpoint.beginRecovery();
        endpoints.save(endpoint);
        if (isLlamaCpp(endpoint)) {
            if (loading) {
                String requestedModelKey = modelKey;
                boolean confirmedLoaded = deployments.findByRuntimeEndpointId(endpoint.getId()).stream()
                        .anyMatch(item -> item.getProviderModelId().equals(requestedModelKey) && item.isLoaded());
                if (!confirmedLoaded) {
                    endpoint.failRecovery();
                    endpoints.save(endpoint);
                    throw rejected("MODEL_LOAD_STATE_NOT_CONFIRMED", "llama.cpp accepted the operation, but the refreshed model catalog does not confirm the model is loaded yet. Refresh model synchronization and check its status.");
                }
            }
            endpoint.completeRecovery();
            endpoints.save(endpoint);
            return loading ? "llama.cpp router confirms the model is loaded." : "llama.cpp model unload completed; the router remains available.";
        }
        if (!loading) {
            Optional<ModelDeployment> remaining = deployments.findByRuntimeEndpointId(endpoint.getId()).stream()
                    .filter(ModelDeployment::isLoaded).findFirst();
            if (remaining.isEmpty()) {
                endpoint.failRecovery();
                endpoints.save(endpoint);
                return "Model unloaded. No loaded models remain, so this endpoint stays unavailable until a model is loaded.";
            }
            modelKey = remaining.get().getProviderModelId();
        }

        ObjectNode warm = mapper.createObjectNode().put("model", modelKey).put("stream", false)
                .put("max_tokens", 1).put("temperature", 0);
        warm.putArray("messages").addObject().put("role", "user").put("content", "OK");
        RuntimeResult warmup = inference.chatCompletion(endpoint, warm);
        if (!warmup.isSuccessful()) {
            endpoint.failRecovery();
            endpoints.save(endpoint);
            throw rejected("MODEL_WARMUP_FAILED", "The model operation completed in LM Studio, but the runtime warm-up failed. The endpoint remains unavailable.");
        }
        endpoint.completeRecovery();
        endpoints.save(endpoint);
        return loading ? "Model loaded and endpoint warm-up succeeded." : "Model unloaded and the remaining runtime model warm-up succeeded.";
    }

    private ObjectNode request(RuntimeEndpoint endpoint, LoadCommand command, JsonNode model) {
        if (isLlamaCpp(endpoint)) return mapper.createObjectNode().put("model", command.modelKey());
        String runtimeModelKey = model == null ? command.modelKey() : text(model, "key", command.modelKey());
        ObjectNode node = mapper.createObjectNode().put("model", runtimeModelKey).put("echo_load_config", true);
        // These are the options documented by LM Studio's native v1 load endpoint.
        put(node, "context_length", command.contextLength());
        put(node, "eval_batch_size", command.evalBatchSize());
        put(node, "num_experts", command.numExperts());
        if (command.flashAttention() != null) node.put("flash_attention", command.flashAttention());
        if (command.offloadKvCacheToGpu() != null) node.put("offload_kv_cache_to_gpu", command.offloadKvCacheToGpu());
        return node;
    }

    private ObjectNode unloadRequest(RuntimeEndpoint endpoint, String modelKey) {
        return isLlamaCpp(endpoint) ? mapper.createObjectNode().put("model", modelKey)
                : mapper.createObjectNode().put("instance_id", modelKey);
    }

    private String withWarnings(String message, List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) return message;
        String combined = message + " Warnings: " + String.join(" ", warnings);
        return combined.length() <= 1000 ? combined : combined.substring(0, 997) + "...";
    }

    private String failureMessage(RuntimeEndpoint endpoint, RuntimeResult result) {
        String detail = result.body().path("error").path("message").asText("");
        if (detail.isBlank()) detail = result.body().path("message").asText("");
        return runtimeLabel(endpoint) + " returned HTTP " + result.statusCode() + (detail.isBlank() ? "" : ": " + detail);
    }

    private void put(ObjectNode node, String key, Integer value) {
        if (value != null) node.put(key, value);
    }

    private JsonNode findModel(JsonNode models, String key, String variantKey) {
        for (JsonNode model : models) {
            String modelKey = text(model, "key", text(model, "id", text(model, "name", text(model, "model", ""))));
            if (matchesModelId(key, modelKey)) return model;
            if (matchesModelId(variantKey, modelKey)) return model;
            if (contains(model.path("variants"), key) || contains(model.path("variants"), variantKey)) return model;
            for (JsonNode instance : model.path("loaded_instances")) {
                String instanceId = instance.path("id").asText("");
                if (key != null && key.equals(instanceId)) return model;
                if (variantKey != null && variantKey.equals(instanceId)) return model;
            }
        }
        return null;
    }

    private JsonNode findModelInBody(RuntimeEndpoint endpoint, JsonNode body, String key, String variantKey) {
        if (isLlamaCpp(endpoint)) {
            JsonNode found = findModel(body.path("data"), key, variantKey);
            return found != null ? found : findModel(body.path("models"), key, variantKey);
        }
        return findModel(body.path("models"), key, variantKey);
    }

    private boolean matchesModelId(String requested, String candidate) {
        if (requested == null || candidate == null) return false;
        if (requested.equals(candidate)) return true;
        return modelIdentity(requested).equals(modelIdentity(candidate));
    }

    private String modelIdentity(String value) {
        String normalized = value.trim().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        return normalized.endsWith(".gguf") ? normalized.substring(0, normalized.length() - 5) : normalized;
    }

    private boolean hasLlamaUnsupportedOptions(LoadCommand command) {
        return command.variantKey() != null || command.contextLength() != null || command.evalBatchSize() != null
                || command.physicalBatchSize() != null || command.parallel() != null || command.numExperts() != null
                || command.flashAttention() != null || command.offloadKvCacheToGpu() != null
                || command.gpuOffloadLayers() != null || command.autoUnloadTtlSeconds() != null
                || nonBlank(command.apiIdentifier()) != null || nonBlank(command.gpuOffloadMode()) != null
                || command.gpuOffloadRatio() != null || command.cpuThreadPoolSize() != null
                || command.unifiedKvCache() != null || command.ropeFrequencyBase() != null
                || command.ropeFrequencyScale() != null || command.keepModelInMemory() != null
                || command.tryMmap() != null || command.seed() != null
                || nonBlank(command.kCacheQuantizationType()) != null || nonBlank(command.vCacheQuantizationType()) != null;
    }

    private boolean contains(JsonNode values, String expected) {
        if (expected == null || !values.isArray()) return false;
        for (JsonNode value : values) if (expected.equals(value.asText())) return true;
        return false;
    }

    private List<String> strings(JsonNode values) {
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText());
        return result;
    }

    private String text(JsonNode node, String field, String fallback) {
        return node != null && node.hasNonNull(field) ? node.path(field).asText() : fallback;
    }

    private String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private RuntimeEndpoint endpoint(UUID id) {
        return endpoints.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "The runtime endpoint does not exist."));
    }

    private String runtimeLabel(RuntimeEndpoint endpoint) {
        return endpoint.getRuntimeType() == null ? "Runtime" : endpoint.getRuntimeType().displayName();
    }

    private void requireNativeManagement(RuntimeEndpoint endpoint) {
        RuntimeType type = endpoint.getRuntimeType() == null ? RuntimeType.LM_STUDIO : endpoint.getRuntimeType();
        if (type != RuntimeType.LM_STUDIO && type != RuntimeType.LLAMA_CPP) {
            throw rejected("RUNTIME_MODEL_MANAGEMENT_UNSUPPORTED", type.displayName() + " does not expose a supported model load/unload API through AIConnect.");
        }
    }

    private void requireLmStudioManagement(RuntimeEndpoint endpoint) {
        if (endpoint.getRuntimeType() != null && endpoint.getRuntimeType() != RuntimeType.LM_STUDIO) {
            throw rejected("RUNTIME_MODEL_MANAGEMENT_UNSUPPORTED", "Download management is currently supported only by LM Studio. Download models in llama.cpp's model directory, then synchronize the runtime.");
        }
    }

    private boolean isLlamaCpp(RuntimeEndpoint endpoint) { return endpoint.getRuntimeType() == RuntimeType.LLAMA_CPP; }

    private ApiException rejected(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { return "{}"; }
    }

    public record LoadCommand(String modelKey, String variantKey, Integer contextLength, Integer evalBatchSize,
                              Integer physicalBatchSize, Integer parallel, Integer numExperts, Boolean flashAttention,
                              Boolean offloadKvCacheToGpu, Integer gpuOffloadLayers, Integer autoUnloadTtlSeconds,
                              String apiIdentifier, String gpuOffloadMode, java.math.BigDecimal gpuOffloadRatio, Integer cpuThreadPoolSize,
                              Boolean unifiedKvCache, java.math.BigDecimal ropeFrequencyBase,
                              java.math.BigDecimal ropeFrequencyScale, Boolean keepModelInMemory, Boolean tryMmap,
                              Integer seed, String kCacheQuantizationType, String vCacheQuantizationType) {
        public LoadCommand(String modelKey, Integer contextLength, Integer evalBatchSize, Integer physicalBatchSize,
                           Integer parallel, Integer numExperts, Boolean flashAttention,
                           Boolean offloadKvCacheToGpu, Integer gpuOffloadLayers,
                           Integer autoUnloadTtlSeconds) {
            this(modelKey, null, contextLength, evalBatchSize, physicalBatchSize, parallel, numExperts,
                    flashAttention, offloadKvCacheToGpu, gpuOffloadLayers, autoUnloadTtlSeconds,
                    null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    public record PreflightResult(String modelKey, String displayName, long modelSizeBytes, long heuristicMemoryBytes,
                                  int maxContextLength, int requestedContextLength, boolean compatible,
                                  List<String> warnings, boolean alreadyLoaded, List<String> variants,
                                  String selectedVariant, String requestedVariant, boolean variantAvailable,
                                  boolean variantSelectionSupported, List<String> nativeSupportedOptions) { }
}
