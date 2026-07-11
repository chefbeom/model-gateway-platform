package com.aiconnect.llmgateway.monitoring;

import com.aiconnect.llmgateway.domain.Incident;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.notification.*;
import com.aiconnect.llmgateway.repository.IncidentRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/organizations/{organizationId}/incidents")
public class IncidentQueryController {
    private final InferenceNodeRepository nodes;
    private final RuntimeEndpointRepository endpoints;
    private final IncidentRepository incidents;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationChannelRepository channels;

    public IncidentQueryController(InferenceNodeRepository nodes, RuntimeEndpointRepository endpoints,
                                   IncidentRepository incidents, NotificationDeliveryRepository deliveries,
                                   NotificationChannelRepository channels) {
        this.nodes = nodes; this.endpoints = endpoints; this.incidents = incidents;
        this.deliveries = deliveries; this.channels = channels;
    }

    @GetMapping
    public List<IncidentView> incidents(@PathVariable UUID organizationId,
                                        @RequestParam(required = false) String status) {
        List<RuntimeEndpoint> organizationEndpoints = nodes.findByOrganizationId(organizationId).stream()
                .flatMap(node -> endpoints.findByNodeId(node.getId()).stream()).toList();
        if (organizationEndpoints.isEmpty()) return List.of();
        Map<UUID, RuntimeEndpoint> endpointById = organizationEndpoints.stream()
                .collect(Collectors.toMap(RuntimeEndpoint::getId, Function.identity()));
        List<Incident> found = incidents.findByRuntimeEndpointIdInOrderByOpenedAtDesc(endpointById.keySet()).stream()
                .filter(incident -> status == null || status.isBlank() || incident.getStatus().equalsIgnoreCase(status)).toList();
        if (found.isEmpty()) return List.of();

        Map<UUID, NotificationChannel> channelById = channels.findByOrganizationId(organizationId).stream()
                .collect(Collectors.toMap(NotificationChannel::getId, Function.identity()));
        Map<UUID, List<NotificationDelivery>> deliveryByIncident = deliveries
                .findByIncidentIdInOrderByCreatedAtAsc(found.stream().map(Incident::getId).toList()).stream()
                .collect(Collectors.groupingBy(NotificationDelivery::getIncidentId, LinkedHashMap::new, Collectors.toList()));

        return found.stream().map(incident -> {
            RuntimeEndpoint endpoint = endpointById.get(incident.getRuntimeEndpointId());
            List<DeliveryView> attempts = deliveryByIncident.getOrDefault(incident.getId(), List.of()).stream()
                    .map(delivery -> DeliveryView.from(delivery, channelById.get(delivery.getNotificationChannelId()))).toList();
            return new IncidentView(incident.getId(), incident.getRuntimeEndpointId(), endpoint == null ? null : endpoint.getBaseUrl(),
                    incident.getStatus(), incident.getReason(), incident.getOpenedAt(), incident.getRecoveredAt(), attempts);
        }).toList();
    }

    public record IncidentView(UUID id, UUID runtimeEndpointId, String endpointBaseUrl, String status,
                               String reason, Instant openedAt, Instant recoveredAt, List<DeliveryView> deliveries) { }

    public record DeliveryView(UUID id, UUID channelId, NotificationChannelType channelType, String eventType,
                               String status, String errorMessage, Instant createdAt) {
        static DeliveryView from(NotificationDelivery delivery, NotificationChannel channel) {
            return new DeliveryView(delivery.getId(), delivery.getNotificationChannelId(),
                    channel == null ? null : channel.getChannelType(), delivery.getEventType(), delivery.getStatus(),
                    delivery.getErrorMessage(), delivery.getCreatedAt());
        }
    }
}
