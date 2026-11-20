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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_authorization;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class OrganizationAuthorizationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TotpService totpService;

    @Test
    void organizationAdminIsLimitedToTheirOrganization() throws Exception {
        String ownerToken = verifiedToken(post("/api/auth/bootstrap"), "{\"email\":\"owner2@example.com\",\"password\":\"correct-horse-battery-staple\"}");
        String organizationResponse = mvc.perform(post("/api/admin/organizations").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String organizationId = objectMapper.readTree(organizationResponse).path("id").asText();
        mvc.perform(post("/api/admin/nodes").header("Authorization", "Bearer " + ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + organizationId + "\",\"name\":\"platform-node\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/organizations/{organizationId}/audit-logs", organizationId)
                        .param("action", "ADMIN_CONFIGURATION_CHANGED")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        String userResponse = mvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"operator@example.com\",\"password\":\"correct-horse-battery-staple\",\"platformAdmin\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String userId = objectMapper.readTree(userResponse).path("id").asText();
        mvc.perform(put("/api/admin/organizations/{organizationId}/members/{userId}", organizationId, userId).header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ORGANIZATION_ADMIN\"}"))
                .andExpect(status().isOk());

        String orgAdminToken = verifiedToken(post("/api/auth/login"), "{\"email\":\"operator@example.com\",\"password\":\"correct-horse-battery-staple\"}");
        mvc.perform(post("/api/admin/nodes").header("Authorization", "Bearer " + orgAdminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + organizationId + "\",\"name\":\"private-node\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/organizations/{organizationId}/audit-logs", organizationId)
                        .param("action", "ADMIN_CONFIGURATION_CHANGED")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/organizations").header("Authorization", "Bearer " + orgAdminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unauthorized organization\"}"))
                .andExpect(status().isForbidden());
    }

    private String verifiedToken(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) throws Exception {
        String response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String preMfa = objectMapper.readTree(response).path("accessToken").asText();
        String setupJson = mvc.perform(post("/api/auth/totp/setup").header("Authorization", "Bearer " + preMfa))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = objectMapper.readTree(setupJson).path("secret").asText();
        String code = totpService.generateCode(secret, Instant.now());
        String enrollJson = mvc.perform(post("/api/auth/totp/enroll")
                        .header("Authorization", "Bearer " + preMfa)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(enrollJson).path("session").path("accessToken").asText();
    }
}
