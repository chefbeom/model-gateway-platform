package com.aiconnect.llmgateway.modelops;

import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Native LM Studio model administration. The endpoint-scoped path is also protected by the
 * organization authorization filter, so only an organization administrator can perform these actions.
 */
@RestController
@RequestMapping("/api/admin/runtime-endpoints/{endpointId}")
public class RuntimeModelOperationController {
    private final RuntimeModelOperationService service;

    public RuntimeModelOperationController(RuntimeModelOperationService service) {
        this.service = service;
    }

    @PostMapping("/model-operations/preflight")
    public RuntimeModelOperationService.PreflightResult preflight(@PathVariable UUID endpointId,
                                                                    @Valid @RequestBody LoadRequest request) {
        return service.preflight(endpointId, request.command());
    }

    @PostMapping("/model-operations/load")
    public OperationView load(@PathVariable UUID endpointId, @Valid @RequestBody LoadRequest request) {
        RuntimeModelOperationService.PreflightResult check = service.preflight(endpointId, request.command());
        if (!check.compatible()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_CONFIGURATION_INCOMPATIBLE",
                    "The requested context length exceeds the model capability. Correct the configuration and try again.");
        }
        return OperationView.from(service.load(endpointId, request.command(), null));
    }

    @PostMapping("/model-operations/unload")
    public OperationView unload(@PathVariable UUID endpointId, @Valid @RequestBody ModelRequest request) {
        return OperationView.from(service.unload(endpointId, request.modelKey()));
    }

    @PostMapping("/model-operations/download")
    public OperationView download(@PathVariable UUID endpointId, @Valid @RequestBody DownloadRequest request) {
        return OperationView.from(service.download(endpointId, request.modelKey(), request.quantization()));
    }

    @GetMapping("/model-operations/downloads/{jobId}")
    public RuntimeResult downloadStatus(@PathVariable UUID endpointId, @PathVariable String jobId) {
        return service.downloadStatus(endpointId, jobId);
    }

    @GetMapping("/model-operations")
    public List<OperationView> operations(@PathVariable UUID endpointId) {
        return service.operations(endpointId).stream().map(OperationView::from).toList();
    }

    @GetMapping("/model-profiles")
    public List<ProfileView> profiles(@PathVariable UUID endpointId) {
        return service.profiles(endpointId).stream().map(ProfileView::from).toList();
    }

    @PostMapping("/model-profiles")
    public ProfileView saveProfile(@PathVariable UUID endpointId, @Valid @RequestBody SaveProfileRequest request) {
        return ProfileView.from(service.saveProfile(endpointId, request.name(), request.command().command()));
    }

    @PostMapping("/model-profiles/{profileId}/apply")
    public OperationView applyProfile(@PathVariable UUID endpointId, @PathVariable UUID profileId) {
        return OperationView.from(service.applyProfile(endpointId, profileId));
    }

    public record ModelRequest(@NotBlank @Size(max = 500) String modelKey) { }

    public record DownloadRequest(@NotBlank @Size(max = 500) String modelKey, @Size(max = 80) String quantization) { }

    public record LoadRequest(
            @NotBlank @Size(max = 500) String modelKey,
            @Min(1) Integer contextLength,
            @Min(1) Integer evalBatchSize,
            @Min(1) Integer physicalBatchSize,
            @Min(1) Integer parallel,
            @Min(1) Integer numExperts,
            Boolean flashAttention,
            Boolean offloadKvCacheToGpu,
            @Min(0) Integer gpuOffloadLayers,
            @Min(1) Integer autoUnloadTtlSeconds
    ) {
        RuntimeModelOperationService.LoadCommand command() {
            return new RuntimeModelOperationService.LoadCommand(modelKey, contextLength, evalBatchSize,
                    physicalBatchSize, parallel, numExperts, flashAttention, offloadKvCacheToGpu,
                    gpuOffloadLayers, autoUnloadTtlSeconds);
        }
    }

    public record SaveProfileRequest(@NotBlank @Size(max = 120) String name, @NotNull @Valid LoadRequest command) { }

    public record ProfileView(UUID id, String name, String modelKey, String configJson) {
        static ProfileView from(RuntimeModelProfile profile) {
            return new ProfileView(profile.getId(), profile.getName(), profile.getModelKey(), profile.getConfigJson());
        }
    }

    public record OperationView(UUID id, UUID profileId, String modelKey, String operationType, String status,
                                String message, String resultJson, Instant createdAt, Instant completedAt) {
        static OperationView from(RuntimeModelOperation operation) {
            return new OperationView(operation.getId(), operation.getProfileId(), operation.getModelKey(),
                    operation.getOperationType(), operation.getStatus(), operation.getMessage(),
                    operation.getResultJson(), operation.getCreatedAt(), operation.getCompletedAt());
        }
    }
}
