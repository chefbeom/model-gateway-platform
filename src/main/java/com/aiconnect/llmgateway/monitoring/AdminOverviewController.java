package com.aiconnect.llmgateway.monitoring;

import com.aiconnect.llmgateway.domain.Incident;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.RequestStatus;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.repository.IncidentRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminOverviewController {
    private final LlmRequestRepository requests;
    private final RuntimeEndpointRepository endpoints;
    private final ProjectRepository projects;
    private final InferenceNodeRepository nodes;
    private final IncidentRepository incidents;

    public AdminOverviewController(LlmRequestRepository requests, RuntimeEndpointRepository endpoints,
                                   ProjectRepository projects, InferenceNodeRepository nodes,
                                   IncidentRepository incidents) {
        this.requests = requests;
        this.endpoints = endpoints;
        this.projects = projects;
        this.nodes = nodes;
        this.incidents = incidents;
    }

    @GetMapping("/overview")
    public Overview platformOverview() {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        return summarize(requests.findByStartedAtAfter(since), endpoints.findAll(), incidents.findAll());
    }

    @GetMapping("/organizations/{organizationId}/overview")
    public Overview organizationOverview(@PathVariable UUID organizationId) {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        List<UUID> projectIds = projects.findByOrganizationId(organizationId).stream().map(Project::getId).toList();
        List<LlmRequest> scopedRequests = projectIds.isEmpty()
                ? List.of()
                : requests.findByProjectIdInAndStartedAtAfter(projectIds, since);
        List<RuntimeEndpoint> scopedEndpoints = nodes.findByOrganizationId(organizationId).stream()
                .flatMap(node -> endpoints.findByNodeId(node.getId()).stream())
                .toList();
        List<Incident> scopedIncidents = scopedEndpoints.isEmpty()
                ? List.of()
                : incidents.findByRuntimeEndpointIdInOrderByOpenedAtDesc(
                        scopedEndpoints.stream().map(RuntimeEndpoint::getId).toList());
        return summarize(scopedRequests, scopedEndpoints, scopedIncidents);
    }

    private Overview summarize(List<LlmRequest> window, List<RuntimeEndpoint> endpointWindow,
                               List<Incident> incidentWindow) {
        long succeeded = window.stream().filter(request -> request.getStatus() == RequestStatus.SUCCEEDED).count();
        long failed = window.stream().filter(request -> request.getStatus() == RequestStatus.FAILED).count();
        long active = window.stream().filter(request -> request.getStatus() == RequestStatus.IN_PROGRESS).count();
        long completed = succeeded + failed;
        int inputTokens = window.stream().map(LlmRequest::getInputTokens).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).sum();
        int outputTokens = window.stream().map(LlmRequest::getOutputTokens).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).sum();
        long failovers = window.stream().mapToLong(LlmRequest::getFailoverCount).sum();
        BigDecimal estimatedCost = window.stream().map(LlmRequest::getEstimatedCost).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<Currency, BigDecimal> estimatedCostByCurrency = new EnumMap<>(Currency.class);
        window.stream()
                .filter(request -> request.getEstimatedCost() != null)
                .forEach(request -> estimatedCostByCurrency.merge(
                        request.getCostCurrency() == null ? Currency.KRW : request.getCostCurrency(),
                        request.getEstimatedCost(), BigDecimal::add));
        long unhealthyEndpoints = endpointWindow.stream()
                .filter(endpoint -> endpoint.getHealthStatus().name().equals("UNHEALTHY")).count();
        long openIncidents = incidentWindow.stream().filter(incident -> "OPEN".equals(incident.getStatus())).count();
        double successRate = completed == 0 ? 1.0d : (double) succeeded / completed;
        double errorRate = completed == 0 ? 0.0d : (double) failed / completed;
        return new Overview(window.size(), succeeded, failed, active, successRate, errorRate,
                inputTokens, outputTokens, estimatedCost, estimatedCostByCurrency, percentile95(window), failovers,
                endpointWindow.size(), unhealthyEndpoints, openIncidents);
    }

    private long percentile95(List<LlmRequest> window) {
        List<Long> values = window.stream().map(LlmRequest::getLatencyMs).filter(Objects::nonNull).sorted().toList();
        if (values.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(values.size() * 0.95d) - 1);
        return values.get(index);
    }

    public record Overview(
            int requests24h,
            long succeeded24h,
            long failed24h,
            long activeRequests,
            double successRate24h,
            double errorRate24h,
            int inputTokens24h,
            int outputTokens24h,
            BigDecimal estimatedCost24h,
            Map<Currency, BigDecimal> estimatedCostByCurrency,
            long p95LatencyMs24h,
            long failovers24h,
            long endpoints,
            long unhealthyEndpoints,
            long openIncidents
    ) { }
}
