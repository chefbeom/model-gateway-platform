package com.aiconnect.llmgateway.modelops;

import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
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
        RuntimeResult result = models.list(endpoint);
        if (!result.isSuccessful()) throw rejected("MODEL_LIST_FAILED", "LM Studio rejected the model list request.");
        JsonNode model = findModel(result.body().path("models"), command.modelKey(), command.variantKey());
        if (model == null) throw rejected("MODEL_NOT_AVAILABLE", "The requested model is not downloaded on this runtime.");

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
        ObjectNode request = mapper.createObjectNode().put("model", modelKey);
        if (quantization != null && !quantization.isBlank()) request.put("quantization", quantization);
        RuntimeModelOperation operation = operations.save(new RuntimeModelOperation(endpointId, null, modelKey, "DOWNLOAD", json(request)));
        try {
            RuntimeResult result = models.download(endpoint, request);
            if (!result.isSuccessful()) operation.fail(failureMessage(result));
            else operation.complete(json(result.body()), result.body().path("status").asText("Download requested."));
        } catch (RuntimeException exception) {
            operation.fail(exception.getMessage());
        }
        return operations.save(operation);
    }

    @Transactional(readOnly = true)
    public RuntimeResult downloadStatus(UUID endpointId, String jobId) {
        return models.downloadStatus(endpoint(endpointId), jobId);
    }

    private RuntimeModelOperation apply(UUID endpointId, LoadCommand command, UUID profileId, String type, boolean safe) {
        RuntimeEndpoint endpoint = endpoint(endpointId);
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
            if (!listing.isSuccessful()) throw rejected("MODEL_LIST_FAILED", "LM Studio rejected the model list request.");
            model = findModel(listing.body().path("models"), command.modelKey(), command.variantKey());
            if (model == null) throw rejected("MODEL_NOT_AVAILABLE", "The requested model is not downloaded on this runtime.");
            warmupModelKey = text(model, "key", command.modelKey());
        }
        // LM Studio's native unload endpoint does not accept a model catalog key. It requires instance_id.
        ObjectNode request = "LOAD".equals(type) ? request(command, model) : unloadRequest(command.modelKey());
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
                operation.fail(failureMessage(result));
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
        }
        return operations.save(operation);
    }

    private String recover(RuntimeEndpoint endpoint, String modelKey, boolean loading) {
        endpoint.beginRecovery();
        endpoints.save(endpoint);
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

    private ObjectNode request(LoadCommand command, JsonNode model) {
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

    private ObjectNode unloadRequest(String instanceId) {
        return mapper.createObjectNode().put("instance_id", instanceId);
    }

    private String withWarnings(String message, List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) return message;
        String combined = message + " Warnings: " + String.join(" ", warnings);
        return combined.length() <= 1000 ? combined : combined.substring(0, 997) + "...";
    }

    private String failureMessage(RuntimeResult result) {
        String detail = result.body().path("error").path("message").asText("");
        if (detail.isBlank()) detail = result.body().path("message").asText("");
        return "LM Studio returned HTTP " + result.statusCode() + (detail.isBlank() ? "" : ": " + detail);
    }

    private void put(ObjectNode node, String key, Integer value) {
        if (value != null) node.put(key, value);
    }

    private JsonNode findModel(JsonNode models, String key, String variantKey) {
        for (JsonNode model : models) {
            String modelKey = model.path("key").asText("");
            if (key != null && (key.equals(modelKey) || key.equals(model.path("id").asText()))) return model;
            if (variantKey != null && (variantKey.equals(modelKey) || variantKey.equals(model.path("id").asText()))) return model;
            if (contains(model.path("variants"), key) || contains(model.path("variants"), variantKey)) return model;
            for (JsonNode instance : model.path("loaded_instances")) {
                String instanceId = instance.path("id").asText("");
                if (key != null && key.equals(instanceId)) return model;
                if (variantKey != null && variantKey.equals(instanceId)) return model;
            }
        }
        return null;
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
