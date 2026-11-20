package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.identity.TotpService;
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

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_organization_discovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class OrganizationDiscoveryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TotpService totpService;

    @Test
    void platformAdminSeesAllWhileOrganizationAdminDiscoversOnlyMembershipScope() throws Exception {
        String platformToken = verifiedToken(post("/api/auth/bootstrap"),
                "{\"email\":\"discovery-owner@example.com\",\"password\":\"correct-horse-battery-staple\"}");
        String firstOrganization = id(postJson("/api/admin/organizations", platformToken, "{\"name\":\"First Organization\"}"));
        String secondOrganization = id(postJson("/api/admin/organizations", platformToken, "{\"name\":\"Second Organization\"}"));
        String firstProject = id(postJson("/api/admin/projects", platformToken,
                "{\"organizationId\":\"" + firstOrganization + "\",\"name\":\"First Project\"}"));
        postJson("/api/admin/projects", platformToken,
                "{\"organizationId\":\"" + secondOrganization + "\",\"name\":\"Second Project\"}");

        String userResponse = postJson("/api/admin/users", platformToken,
                "{\"email\":\"scoped-admin@example.com\",\"password\":\"correct-horse-battery-staple\",\"platformAdmin\":false}");
        String userId = id(userResponse);
        mvc.perform(put("/api/admin/organizations/{organizationId}/members/{userId}", firstOrganization, userId)
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ORGANIZATION_ADMIN\"}"))
                .andExpect(status().isOk());
        String scopedToken = verifiedToken(post("/api/auth/login"),
                "{\"email\":\"scoped-admin@example.com\",\"password\":\"correct-horse-battery-staple\"}");

        mvc.perform(get("/api/admin/organizations").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/admin/organizations").header("Authorization", "Bearer " + scopedToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(firstOrganization));
        mvc.perform(get("/api/admin/organizations/{organizationId}/projects", firstOrganization)
                        .header("Authorization", "Bearer " + scopedToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/admin/organizations/{organizationId}/projects", secondOrganization)
                        .header("Authorization", "Bearer " + scopedToken))
                .andExpect(status().isForbidden());
    }

    private String postJson(String path, String token, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String verifiedToken(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) throws Exception {
        String response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
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

    private String id(String response) throws Exception {
        JsonNode parsed = objectMapper.readTree(response);
        return parsed.path("id").asText();
    }
}
