package com.aiconnect.llmgateway.external;

import com.aiconnect.llmgateway.domain.Currency;

import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class ExternalProviderAdministrationController {
    private final ExternalProviderAdministrationService service;
    public ExternalProviderAdministrationController(ExternalProviderAdministrationService service) { this.service = service; }

    @GetMapping("/organizations/{organizationId}/external-providers")
    public List<ExternalProviderAdministrationService.ProviderView> list(@PathVariable UUID organizationId) { return service.list(organizationId); }

    @PostMapping("/external-providers")
    public ExternalProviderAdministrationService.ProviderView create(@Valid @RequestBody CreateProvider request) { return service.create(request.organizationId(), request.displayName(), request.baseUrl(), request.apiKey()); }

    @PatchMapping("/external-providers/{providerId}")
    public ExternalProviderAdministrationService.ProviderView update(@PathVariable UUID providerId, @RequestBody UpdateProvider request) { return service.update(providerId, request.displayName(), request.baseUrl(), request.apiKey(), request.enabled()); }

    @GetMapping("/external-providers/{providerId}/deletion-preview")
    public ExternalProviderAdministrationService.ProviderDeletePreview deletionPreview(@PathVariable UUID providerId) { return service.deletionPreview(providerId); }

    @DeleteMapping("/external-providers/{providerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID providerId, @RequestParam(defaultValue = "false") boolean force,
                       @RequestParam(defaultValue = "false") boolean purgeHistory, HttpServletRequest request) {
        if ((force || purgeHistory) && !isPlatformAdmin(request)) {
            throw new com.aiconnect.llmgateway.web.ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED", "Only the Platform administrator can force provider cleanup or purge request history.");
        }
        service.delete(providerId, force, purgeHistory);
    }

    @PostMapping("/external-providers/{providerId}/probe")
    public ExternalProviderAdministrationService.ProbeView probe(@PathVariable UUID providerId) { return service.probe(providerId); }

    @GetMapping("/external-providers/{providerId}/models")
    public List<ExternalProviderAdministrationService.ProviderModelView> models(@PathVariable UUID providerId) { return service.models(providerId); }

    @PostMapping("/external-providers/{providerId}/models")
    public ExternalProviderAdministrationService.ProviderModelView addModel(@PathVariable UUID providerId, @Valid @RequestBody AddModel request) {
        return service.addModel(providerId, request.providerModelId(), request.displayName(), request.compatibilityKey(), request.contextLength(), request.maxConcurrency(), request.capabilitiesJson(), request.inputPricePerMillion(), request.outputPricePerMillion(), request.currency());
    }

    private boolean isPlatformAdmin(HttpServletRequest request) {
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        return Boolean.TRUE.equals(request.getAttribute("aiconnect.platform-admin")) || (actor != null && actor.platformAdmin());
    }

    public record CreateProvider(@NotNull UUID organizationId, @NotBlank @Size(max = 160) String displayName,
                                 @Size(max = 500) String baseUrl, @NotBlank @Size(max = 500) String apiKey) { }
    public record UpdateProvider(String displayName, String baseUrl, String apiKey, Boolean enabled) { }
    public record AddModel(@NotBlank @Size(max = 500) String providerModelId, @NotBlank @Size(max = 200) String displayName,
                           String compatibilityKey, @Positive Integer contextLength, @Positive Integer maxConcurrency,
                           String capabilitiesJson, @PositiveOrZero BigDecimal inputPricePerMillion,
                           @PositiveOrZero BigDecimal outputPricePerMillion, Currency currency) { }
}
