package com.aiconnect.llmgateway.health;

import com.aiconnect.llmgateway.domain.HealthStatus;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/runtime-endpoints/{endpointId}")
public class EndpointOperationsController {
    private final EndpointOperationsService operations;
    public EndpointOperationsController(EndpointOperationsService operations) { this.operations = operations; }
    @PostMapping("/drain") public EndpointState drain(@PathVariable UUID endpointId) { return EndpointState.from(operations.drain(endpointId)); }
    @PostMapping("/resume") public EndpointState resume(@PathVariable UUID endpointId) { return EndpointState.from(operations.resume(endpointId)); }
    public record EndpointState(UUID endpointId, HealthStatus state) { static EndpointState from(RuntimeEndpoint endpoint) { return new EndpointState(endpoint.getId(), endpoint.getHealthStatus()); } }
}
