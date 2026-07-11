package com.aiconnect.llmgateway.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Separate deletion endpoint so logical-service removal always performs a reference check first. */
@RestController
@RequestMapping("/api/admin/services")
public class ServiceDeletionController {
    private final ServiceDeletionService deletion;

    public ServiceDeletionController(ServiceDeletionService deletion) {
        this.deletion = deletion;
    }

    @GetMapping("/{serviceId}/deletion-check")
    public ServiceDeletionService.DeletionCheck deletionCheck(@PathVariable UUID serviceId) {
        return deletion.inspect(serviceId);
    }

    @DeleteMapping("/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteService(@PathVariable UUID serviceId) {
        deletion.deleteIfUnused(serviceId);
    }
}
