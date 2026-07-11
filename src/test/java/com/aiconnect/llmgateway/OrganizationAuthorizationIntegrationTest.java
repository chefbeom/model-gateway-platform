package com.aiconnect.llmgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_authorization;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class OrganizationAuthorizationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void organizationAdminIsLimitedToTheirOrganization() throws Exception {
        String ownerToken = token(post("/api/auth/bootstrap"), "{\"email\":\"owner2@example.com\",\"password\":\"correct-horse-battery-staple\"}");
        String organizationResponse = mvc.perform(post("/api/admin/organizations").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String organizationId = objectMapper.readTree(organizationResponse).path("id").asText();
        String userResponse = mvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"operator@example.com\",\"password\":\"correct-horse-battery-staple\",\"platformAdmin\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String userId = objectMapper.readTree(userResponse).path("id").asText();
        mvc.perform(put("/api/admin/organizations/{organizationId}/members/{userId}", organizationId, userId).header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ORGANIZATION_ADMIN\"}"))
                .andExpect(status().isOk());

        String orgAdminToken = token(post("/api/auth/login"), "{\"email\":\"operator@example.com\",\"password\":\"correct-horse-battery-staple\"}");
        mvc.perform(post("/api/admin/nodes").header("Authorization", "Bearer " + orgAdminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + organizationId + "\",\"name\":\"private-node\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/organizations").header("Authorization", "Bearer " + orgAdminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unauthorized organization\"}"))
                .andExpect(status().isForbidden());
    }

    private String token(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) throws Exception {
        String response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("accessToken").asText();
    }
}
