package com.aiconnect.llmgateway.health;

import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.domain.HealthStatus;
import com.aiconnect.llmgateway.domain.Incident;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.notification.NotificationService;
import com.aiconnect.llmgateway.repository.IncidentRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RuntimeHealthMonitor {
    private final RuntimeEndpointRepository endpoints;
    private final IncidentRepository incidents;
    private final ControlPlaneService controlPlane;
    private final RecoveryWarmupService warmup;
    private final NotificationService notifications;
    public RuntimeHealthMonitor(RuntimeEndpointRepository endpoints, IncidentRepository incidents, ControlPlaneService controlPlane,
                                RecoveryWarmupService warmup, NotificationService notifications) {
        this.endpoints = endpoints; this.incidents = incidents; this.controlPlane = controlPlane; this.warmup = warmup; this.notifications = notifications;
    }
    @Scheduled(fixedDelayString = "${gateway.health-check-delay-ms:30000}")
    @Transactional
    public void checkEndpoints() {
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
