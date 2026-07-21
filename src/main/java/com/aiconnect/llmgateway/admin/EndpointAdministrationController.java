package com.aiconnect.llmgateway.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/runtime-endpoints/{endpointId}")
public class EndpointAdministrationController {
    private final EndpointAdministrationService service;

    public EndpointAdministrationController(EndpointAdministrationService service) {
        this.service = service;
    }

    @GetMapping
    public EndpointAdministrationService.EndpointDetail detail(@PathVariable UUID endpointId) {
        return service.detail(endpointId);
    }

    @PatchMapping
    public AdminController.EndpointView update(@PathVariable UUID endpointId, @Valid @RequestBody UpdateEndpoint request) {
        return AdminController.EndpointView.from(service.update(endpointId,
                new EndpointAdministrationService.UpdateCommand(request.displayName(), request.baseUrl(), request.apiToken(), request.clearApiToken(), request.enabled())));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID endpointId) {
        service.archive(endpointId);
    }

    public record UpdateEndpoint(@Size(max = 160) String displayName, @Size(max = 500) String baseUrl, @Size(max = 2000) String apiToken,
                                 boolean clearApiToken, Boolean enabled) { }
}
