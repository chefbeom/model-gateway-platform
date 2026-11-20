package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.identity.TotpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_accelerator;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class AcceleratorInventoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TotpService totpService;

    @Test
    void acceptsUnknownFutureAcceleratorAsNodeMetadata() throws Exception {
        String credentials = "{\"email\":\"hardware@example.com\",\"password\":\"correct-horse-battery-staple\"}";
        String preMfa = objectMapper.readTree(mvc.perform(post("/api/auth/bootstrap").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("accessToken").asText();
        String setupJson = mvc.perform(post("/api/auth/totp/setup").header("Authorization", "Bearer " + preMfa))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = objectMapper.readTree(setupJson).path("secret").asText();
        String code = totpService.generateCode(secret, Instant.now());
        String enrollJson = mvc.perform(post("/api/auth/totp/enroll")
                        .header("Authorization", "Bearer " + preMfa)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(enrollJson).path("session").path("accessToken").asText();

        String organizationId = objectMapper.readTree(mvc.perform(post("/api/admin/organizations").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hardware Lab\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();
        String nodeId = objectMapper.readTree(mvc.perform(post("/api/admin/nodes").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + organizationId + "\",\"name\":\"future-host\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();

        String response = mvc.perform(post("/api/admin/nodes/{nodeId}/accelerators", nodeId).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"FutureVendor\",\"productName\":\"FutureGPU X1000\",\"deviceIndex\":0,\"memoryTotalMb\":262144}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(response).path("productName").asText()).isEqualTo("FutureGPU X1000");
    }
}
