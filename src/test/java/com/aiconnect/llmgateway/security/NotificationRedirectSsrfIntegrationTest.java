package com.aiconnect.llmgateway.security;

import com.aiconnect.llmgateway.domain.Incident;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.notification.NotificationChannelService;
import com.aiconnect.llmgateway.notification.NotificationChannelType;
import com.aiconnect.llmgateway.notification.NotificationDelivery;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification_redirect_ssrf;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "aiconnect.security.external-url.allow-loopback-test-server=true"
})
class NotificationRedirectSsrfIntegrationTest {

    @Autowired OrganizationRepository organizations;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired IncidentRepository incidents;
    @Autowired NotificationChannelService channelService;
    @Autowired NotificationService notificationService;
    @Autowired NotificationDeliveryRepository deliveries;

    @Test
    void discordWebhookClientDoesNotFollowHttpRedirectsToInternalTargets() throws Exception {
        AtomicInteger internalTargetHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/redirect", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + "/internal-secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/internal-secret", exchange -> {
            internalTargetHits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            Organization org = organizations.save(new Organization("Redirect Sec Org"));
            InferenceNode node = nodes.save(new InferenceNode(org.getId(), "node", null, "DIRECT", null));
            RuntimeEndpoint endpoint = endpoints.save(new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://runtime:1234", null));
            channelService.create(org.getId(), NotificationChannelType.DISCORD_WEBHOOK,
                    "http://127.0.0.1:" + port + "/redirect", null);
            Incident incident = incidents.save(new Incident(endpoint.getId(), "Redirect probe"));

            notificationService.incidentOpened(incident, endpoint, "Redirect probe");

            assertThat(internalTargetHits.get()).isZero();
            List<NotificationDelivery> results = deliveries.findByIncidentIdInOrderByCreatedAtAsc(List.of(incident.getId()));
            assertThat(results).singleElement().satisfies(d -> {
                assertThat(d.getStatus()).isEqualTo("FAILED");
                assertThat(d.getErrorMessage()).contains("302");
            });
        } finally {
            server.stop(0);
        }
    }
}
