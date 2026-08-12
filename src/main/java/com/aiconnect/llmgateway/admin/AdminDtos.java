package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.domain.RuntimeType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class AdminDtos {
    private AdminDtos() { }

    public record CreateOrganization(@NotBlank @Size(max = 120) String name) { }
    public record CreateProject(@NotNull UUID organizationId, UUID teamId, @NotBlank @Size(max = 120) String name) { }
    public record CreateNode(@NotNull UUID organizationId, @NotBlank @Size(max = 120) String name,
                             @Size(max = 500) String description, String connectionMode, String labelsJson) { }
    public record CreateEndpoint(@NotNull UUID nodeId, @Size(max = 160) String displayName, @NotNull RuntimeType runtimeType,
                                 @NotBlank @Size(max = 500) String baseUrl, String apiToken) { }
    public record CreateDeployment(@NotNull UUID runtimeEndpointId, @NotBlank @Size(max = 500) String providerModelId,
                                   @Size(max = 500) String compatibilityKey, @NotBlank @Size(max = 200) String displayName,
                                   String modelFamily, String quantization, @Positive Integer contextLength,
                                   @Min(1) Integer maxConcurrency, String capabilitiesJson,
                                   @DecimalMin("0") BigDecimal inputPricePerMillion,
                                   @DecimalMin("0") BigDecimal outputPricePerMillion, Currency currency) { }
    public record CreateService(@NotNull UUID organizationId,
                                @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,119}") String serviceKey,
                                @NotBlank @Size(max = 200) String displayName, FailoverPolicy failoverPolicy,
                                RetryPolicy retryPolicy, boolean allowDegraded, String requiredCapabilitiesJson,
                                @DecimalMin("0") BigDecimal inputPricePerMillion,
                                @DecimalMin("0") BigDecimal outputPricePerMillion, Currency currency) { }
    public record GrantServiceAccess(@NotNull UUID serviceId) { }
    public record CreateTarget(@NotNull UUID deploymentId, @Min(1) int priority, @Min(1) Integer weight,
                               boolean degraded, @Min(1) Integer maxConcurrencyOverride) { }
    public record CreateApiKey(@NotBlank @Size(max = 120) String name, Instant expiresAt) { }
}
