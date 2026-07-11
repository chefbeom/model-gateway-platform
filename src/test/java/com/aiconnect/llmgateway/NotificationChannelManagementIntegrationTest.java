package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.Incident;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.notification.NotificationChannel;
import com.aiconnect.llmgateway.notification.NotificationChannelService;
import com.aiconnect.llmgateway.notification.NotificationChannelType;
import com.aiconnect.llmgateway.notification.NotificationDeliveryRepository;
import com.aiconnect.llmgateway.notification.NotificationService;
import com.aiconnect.llmgateway.repository.IncidentRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_notification_channel_management;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class NotificationChannelManagementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired IncidentRepository incidents;
    @Autowired NotificationChannelService channelService;
    @Autowired NotificationService notificationService;
    @Autowired NotificationDeliveryRepository deliveries;

    @Test
    void disabledChannelIsSkippedAndCannotBeMutatedThroughAnotherOrganization() throws Exception {
        AtomicInteger received = new AtomicInteger();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/notify", exchange -> {
            exchange.getRequestBody().readAllBytes();
            received.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        try {
            Organization owner = organizations.save(new Organization("Channel Owner"));
            Organization other = organizations.save(new Organization("Other Organization"));
            NotificationChannel channel = channelService.create(owner.getId(), NotificationChannelType.DISCORD_WEBHOOK,
                    "http://127.0.0.1:" + receiver.getAddress().getPort() + "/notify", null);

            mvc.perform(patch("/api/admin/organizations/{organizationId}/notification-channels/{channelId}",
                            other.getId(), channel.getId())
                            .header("X-Admin-Token", "integration-admin-token")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isNotFound());

            mvc.perform(patch("/api/admin/organizations/{organizationId}/notification-channels/{channelId}",
                            owner.getId(), channel.getId())
                            .header("X-Admin-Token", "integration-admin-token")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false));

            InferenceNode node = nodes.save(new InferenceNode(owner.getId(), "channel-node", null, "DIRECT", null));
            RuntimeEndpoint endpoint = endpoints.save(new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO,
                    "http://channel-node:1234", null));
            Incident incident = incidents.save(new Incident(endpoint.getId(), "Synthetic failure"));
            notificationService.incidentOpened(incident, endpoint, "Synthetic failure");

            assertThat(received).hasValue(0);
            assertThat(deliveries.findByIncidentIdInOrderByCreatedAtAsc(List.of(incident.getId()))).isEmpty();

            mvc.perform(patch("/api/admin/organizations/{organizationId}/notification-channels/{channelId}",
                            owner.getId(), channel.getId())
                            .header("X-Admin-Token", "integration-admin-token")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));

            notificationService.incidentOpened(incident, endpoint, "Synthetic failure");
            assertThat(received).hasValue(1);
            assertThat(deliveries.findByIncidentIdInOrderByCreatedAtAsc(List.of(incident.getId()))).hasSize(1);
        } finally {
            receiver.stop(0);
        }
    }
}
