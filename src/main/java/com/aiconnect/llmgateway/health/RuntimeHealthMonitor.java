package com.aiconnect.llmgateway.health;

import com.aiconnect.llmgateway.cluster.ClusterTaskCoordinator;
import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.domain.HealthStatus;
import com.aiconnect.llmgateway.domain.Incident;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.notification.NotificationService;
import com.aiconnect.llmgateway.repository.IncidentRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RuntimeHealthMonitor {
    private final RuntimeEndpointRepository endpoints;
    private final IncidentRepository incidents;
    private final ControlPlaneService controlPlane;
    private final RecoveryWarmupService warmup;
    private final NotificationService notifications;
    private final ClusterTaskCoordinator coordinator;
    private final TransactionTemplate transactions;
    public RuntimeHealthMonitor(RuntimeEndpointRepository endpoints, IncidentRepository incidents, ControlPlaneService controlPlane,
                                RecoveryWarmupService warmup, NotificationService notifications, ClusterTaskCoordinator coordinator, TransactionTemplate transactions) {
        this.endpoints = endpoints; this.incidents = incidents; this.controlPlane = controlPlane; this.warmup = warmup; this.notifications = notifications;
        this.coordinator = coordinator;
        this.transactions = transactions;
    }
    @Scheduled(fixedDelayString = "${gateway.health-check-delay-ms:30000}",
            initialDelayString = "${gateway.health-check-initial-delay-ms:30000}")
    public void checkEndpoints() {
        coordinator.runIfLeader("runtime-health", () -> transactions.executeWithoutResult(ignored -> checkEndpointsAsLeader()));
    }
    private void checkEndpointsAsLeader() {
        for (RuntimeEndpoint endpoint : endpoints.findByEnabledTrue()) {
            if (endpoint.getHealthStatus() == HealthStatus.DRAINING) continue;
            boolean reachable = controlPlane.probe(endpoint.getId()).reachable();
            boolean operational = reachable;
            if (reachable && endpoint.getHealthStatus() == HealthStatus.RECOVERING) {
                operational = warmup.warm(endpoint);
                if (operational) endpoint.completeRecovery(); else endpoint.failRecovery();
            }
            if (operational) {
                recoverIncident(endpoint);
            } else if (endpoint.getHealthStatus() == HealthStatus.UNHEALTHY) {
                openIncident(endpoint);
            }
        }
    }
    private void openIncident(RuntimeEndpoint endpoint) {
        incidents.findFirstByRuntimeEndpointIdAndStatusOrderByOpenedAtDesc(endpoint.getId(), "OPEN").orElseGet(() -> {
            Incident incident = incidents.save(new Incident(endpoint.getId(), "Runtime endpoint exceeded its failure threshold or warm-up failed."));
            notifications.incidentOpened(incident, endpoint, "Runtime endpoint exceeded its failure threshold or warm-up failed.");
            return incident;
        });
    }
    private void recoverIncident(RuntimeEndpoint endpoint) {
        incidents.findFirstByRuntimeEndpointIdAndStatusOrderByOpenedAtDesc(endpoint.getId(), "OPEN").ifPresent(incident -> {
            incident.recover(); notifications.incidentRecovered(incident, endpoint);
        });
    }
}
