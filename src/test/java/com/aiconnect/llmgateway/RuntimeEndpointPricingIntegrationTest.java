package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_endpoint_pricing;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class RuntimeEndpointPricingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired InferenceNodeRepository nodes;

    @Test
    void storesAndUpdatesEndpointPricingAndCurrency() throws Exception {
        Organization organization = organizations.save(new Organization("Endpoint pricing"));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "pricing-node", null, "DIRECT", null));
        String authorization = "integration-admin-token";

        String response = mvc.perform(post("/api/admin/runtime-endpoints")
                        .header("X-Admin-Token", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"" + node.getId() + "\",\"runtimeType\":\"LM_STUDIO\",\"baseUrl\":\"http://pricing:1234\",\"inputPricePerMillion\":1.25,\"outputPricePerMillion\":2.5,\"currency\":\"USD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputPricePerMillion").value(1.25))
                .andExpect(jsonPath("$.outputPricePerMillion").value(2.5))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andReturn().getResponse().getContentAsString();
        String endpointId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("id").asText();

        mvc.perform(get("/api/admin/runtime-endpoints/{endpointId}", endpointId)
                        .header("X-Admin-Token", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputPricePerMillion").value(1.25))
                .andExpect(jsonPath("$.currency").value("USD"));

        mvc.perform(patch("/api/admin/runtime-endpoints/{endpointId}", endpointId)
                        .header("X-Admin-Token", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputPricePerMillion\":3.5,\"outputPricePerMillion\":4.75,\"currency\":\"KRW\",\"clearPricing\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputPricePerMillion").value(3.5))
                .andExpect(jsonPath("$.outputPricePerMillion").value(4.75))
                .andExpect(jsonPath("$.currency").value("KRW"));
    }
}
