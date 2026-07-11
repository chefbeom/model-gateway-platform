package com.aiconnect.llmgateway.notification;

import com.aiconnect.llmgateway.alert.UsageAlertDelivery;
import com.aiconnect.llmgateway.alert.UsageAlertDeliveryRepository;
import com.aiconnect.llmgateway.alert.UsageAlertMetric;
import com.aiconnect.llmgateway.domain.Incident;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.service.SecretCipher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

@Service
public class NotificationService {
    private final NotificationChannelRepository channels;
    private final NotificationDeliveryRepository deliveries;
    private final UsageAlertDeliveryRepository usageAlertDeliveries;
    private final InferenceNodeRepository nodes;
    private final SecretCipher cipher;
    private final RestClient restClient;

    public NotificationService(NotificationChannelRepository channels, NotificationDeliveryRepository deliveries,
                               UsageAlertDeliveryRepository usageAlertDeliveries, InferenceNodeRepository nodes,
                               SecretCipher cipher, @Qualifier("notificationRestClient") RestClient notificationRestClient) {
        this.channels = channels;
        this.deliveries = deliveries;
        this.usageAlertDeliveries = usageAlertDeliveries;
        this.nodes = nodes;
        this.cipher = cipher;
        this.restClient = notificationRestClient;
    }

    public void incidentOpened(Incident incident, RuntimeEndpoint endpoint, String reason) {
        notifyIncident(incident, endpoint, "INCIDENT_OPENED", "[CRITICAL] LLM runtime unavailable\nEndpoint: " + endpoint.getBaseUrl() + "\nReason: " + reason);
    }

    public void incidentRecovered(Incident incident, RuntimeEndpoint endpoint) {
        notifyIncident(incident, endpoint, "INCIDENT_RECOVERED", "[RECOVERED] LLM runtime restored\nEndpoint: " + endpoint.getBaseUrl());
    }

    public void usageThresholdExceeded(Project project, UsageAlertMetric metric, BigDecimal observed, BigDecimal threshold) {
        String message = "[USAGE ALERT] " + metric.name()
                + "\nProject: " + project.getName()
                + "\nObserved: " + decimal(observed)
                + "\nThreshold: " + decimal(threshold);
        for (NotificationChannel channel : channels.findByOrganizationIdAndEnabledTrue(project.getOrganizationId())) {
            UsageAlertDelivery delivery = usageAlertDeliveries.save(
                    new UsageAlertDelivery(project.getId(), channel.getId(), metric, observed, threshold));
            try {
                send(channel, message);
                delivery.succeed();
            } catch (RuntimeException exception) {
                delivery.fail(exception.getMessage());
            }
            usageAlertDeliveries.save(delivery);
        }
    }

    private void notifyIncident(Incident incident, RuntimeEndpoint endpoint, String eventType, String message) {
        InferenceNode node = nodes.findById(endpoint.getNodeId()).orElse(null);
        if (node == null) return;
        for (NotificationChannel channel : channels.findByOrganizationIdAndEnabledTrue(node.getOrganizationId())) {
            NotificationDelivery delivery = deliveries.save(new NotificationDelivery(incident.getId(), channel.getId(), eventType));
            try {
                send(channel, message);
                delivery.succeed();
            } catch (RuntimeException exception) {
                delivery.fail(exception.getMessage());
            }
            deliveries.save(delivery);
        }
    }

    private void send(NotificationChannel channel, String message) {
        String target = cipher.decrypt(channel.getEncryptedTarget());
        String secret = cipher.decrypt(channel.getEncryptedSecret());
        if (channel.getChannelType() == NotificationChannelType.DISCORD_WEBHOOK) {
            restClient.post().uri(target).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("content", message)).retrieve().toBodilessEntity();
            return;
        }
        if (secret == null || secret.isBlank()) throw new IllegalStateException("Telegram bot token is not configured.");
        restClient.post().uri("https://api.telegram.org/bot" + secret + "/sendMessage")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("chat_id", target, "text", message))
                .retrieve().toBodilessEntity();
    }

    private String decimal(BigDecimal value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
