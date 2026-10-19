package com.aiconnect.llmgateway.scheduling;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.routing.ActiveRequestRegistry;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequestMapping("/api/admin")
public class SchedulingPolicyController {
    private final SchedulingPolicyService policies;
    private final ActiveRequestRegistry active;
    private final RuntimeEndpointRepository endpoints;
    public SchedulingPolicyController(SchedulingPolicyService policies, ActiveRequestRegistry active, RuntimeEndpointRepository endpoints) { this.policies=policies; this.active=active; this.endpoints=endpoints; }
    @GetMapping("/services/{id}/scheduling") public View get(@PathVariable UUID id) { policies.require(id); return new View(policies.settings(id),active.snapshot(id)); }
    @PatchMapping("/services/{id}/scheduling") public View save(@PathVariable UUID id,@Valid @RequestBody SchedulingSettings settings) { return new View(policies.save(id,settings),active.snapshot(id)); }
    @GetMapping("/runtime-endpoints/{id}/capacity") public Capacity capacity(@PathVariable UUID id) { return new Capacity(endpoint(id).getMaxConcurrentRequests()); }
    @PatchMapping("/runtime-endpoints/{id}/capacity") public Capacity capacity(@PathVariable UUID id,@Valid @RequestBody Capacity value) {
        var endpoint=endpoint(id); endpoint.configureCapacity(value.maxConcurrentRequests()); endpoints.save(endpoint); return value;
    }
    private com.aiconnect.llmgateway.domain.RuntimeEndpoint endpoint(UUID id) { return endpoints.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"ENDPOINT_NOT_FOUND","Runtime이 없습니다.")); }
    public record View(SchedulingSettings settings, QueueSnapshot queue) { }
    public record Capacity(@Min(1) @Max(64) int maxConcurrentRequests) { }
}
