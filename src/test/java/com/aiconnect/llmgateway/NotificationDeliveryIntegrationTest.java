package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.notification.*;
import com.aiconnect.llmgateway.repository.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_notification_delivery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class NotificationDeliveryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired IncidentRepository incidents;
    @Autowired NotificationChannelService channelService;
    @Autowired NotificationService notificationService;
    @Autowired NotificationDeliveryRepository deliveries;

    @Test
    void oneFailedChannelDoesNotPreventOtherDeliveryAndBothAreQueryable() throws Exception {
        HttpServer receiver = receiver();
        try {
            Organization organization = organizations.save(new Organization("Alert Org"));
            InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "alert-node", null, "DIRECT", null));
            RuntimeEndpoint endpoint = endpoints.save(new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://alert-node:1234", null));
            channelService.create(organization.getId(), NotificationChannelType.DISCORD_WEBHOOK,
                    "http://127.0.0.1:" + receiver.getAddress().getPort() + "/ok", null);
            channelService.create(organization.getId(), NotificationChannelType.DISCORD_WEBHOOK,
                    "http://127.0.0.1:" + receiver.getAddress().getPort() + "/fail", null);
            Incident incident = incidents.save(new Incident(endpoint.getId(), "Synthetic failure"));

            notificationService.incidentOpened(incident, endpoint, "Synthetic failure");

            List<NotificationDelivery> recorded = deliveries.findByIncidentIdInOrderByCreatedAtAsc(List.of(incident.getId()));
            assertThat(recorded).extracting(NotificationDelivery::getStatus).containsExactlyInAnyOrder("SENT", "FAILED");
            assertThat(recorded.stream().filter(item -> item.getStatus().equals("FAILED")).findFirst().orElseThrow().getErrorMessage()).isNotBlank();

            mvc.perform(get("/api/admin/organizations/{organizationId}/incidents", organization.getId())
                            .header("X-Admin-Token", "integration-admin-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].endpointBaseUrl").value("http://alert-node:1234"))
                    .andExpect(jsonPath("$[0].deliveries.length()").value(2));
        } finally {
            receiver.stop(0);
        }
    }

    private HttpServer receiver() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.createContext("/fail", exchange -> {
            exchange.getRequestBody().readAllBytes(); byte[] body = "failure".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); return server;
    }
}
