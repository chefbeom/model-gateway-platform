package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.Incident;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:aiconnect_notification_direct_proxy;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "RUNTIME_HTTP_PROXY_URL=http://127.0.0.1:1"
})
class NotificationDirectClientWithRuntimeProxyIntegrationTest {
    @Autowired OrganizationRepository organizations;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired IncidentRepository incidents;
    @Autowired NotificationChannelService channels;
    @Autowired NotificationService notifications;
    @Autowired NotificationDeliveryRepository deliveries;

    @Test
    void notificationDeliveryDoesNotUseTheTailnetRuntimeProxy() throws Exception {
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/discord", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        try {
            Organization organization = organizations.save(new Organization("Direct Notification"));
            InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "direct-notification-node", null, "DIRECT", null));
            RuntimeEndpoint endpoint = endpoints.save(new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO,
                    "http://tailnet-runtime.invalid:1234", null));
            channels.create(organization.getId(), NotificationChannelType.DISCORD_WEBHOOK,
                    "http://127.0.0.1:" + receiver.getAddress().getPort() + "/discord", null);
            Incident incident = incidents.save(new Incident(endpoint.getId(), "Synthetic direct-client check"));

            notifications.incidentOpened(incident, endpoint, "Synthetic direct-client check");

            assertThat(deliveries.findByIncidentIdInOrderByCreatedAtAsc(List.of(incident.getId())))
                    .singleElement().extracting(item -> item.getStatus()).isEqualTo("SENT");
        } finally {
            receiver.stop(0);
        }
    }
}
