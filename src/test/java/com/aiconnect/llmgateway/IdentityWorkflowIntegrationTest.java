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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class IdentityWorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TotpService totpService;

    @Test
    void bootstrapLoginAndPlatformAdminAccessWorkTogether() throws Exception {
        String body = "{\"email\":\"owner@example.com\",\"password\":\"correct-horse-battery-staple\"}";
        String bootstrap = mvc.perform(post("/api/auth/bootstrap").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("aiconnect_refresh", true))
                .andExpect(jsonPath("$.mfaState").value("ENROLLMENT_REQUIRED"))
                .andExpect(jsonPath("$.user.accountTier").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.user.totpEnabled").value(false))
                .andReturn().getResponse().getContentAsString();
        JsonNode issued = objectMapper.readTree(bootstrap);
        String preMfaToken = issued.path("accessToken").asText();
        assertThat(preMfaToken).isNotBlank();

        mvc.perform(get("/api/admin/overview").header("Authorization", "Bearer " + preMfaToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOTP_ENROLLMENT_REQUIRED"));

        String setup = mvc.perform(post("/api/auth/totp/setup").header("Authorization", "Bearer " + preMfaToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secret = objectMapper.readTree(setup).path("secret").asText();
        String enrollCode = totpService.generateCode(secret, Instant.now());

        String enrolled = mvc.perform(post("/api/auth/totp/enroll")
                        .header("Authorization", "Bearer " + preMfaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + enrollCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.mfaState").value("VERIFIED"))
                .andExpect(jsonPath("$.session.user.totpEnabled").value(true))
                .andExpect(jsonPath("$.recoveryCodes.length()").value(8))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(enrolled).path("session").path("accessToken").asText();

        mvc.perform(get("/api/admin/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/organizations").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Default Workspace"));
        mvc.perform(get("/api/portal/session").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(true))
                .andExpect(jsonPath("$.accountTier").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.totpEnabled").value(true))
                .andExpect(jsonPath("$.mfaState").value("VERIFIED"))
                .andExpect(jsonPath("$.memberships.length()").value(1))
                .andExpect(jsonPath("$.memberships[0].role").value("ORGANIZATION_ADMIN"));

        String loginRes = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("aiconnect_refresh", true))
                .andExpect(jsonPath("$.mfaState").value("VERIFICATION_REQUIRED"))
                .andReturn().getResponse().getContentAsString();
        String pendingToken = objectMapper.readTree(loginRes).path("accessToken").asText();
        mvc.perform(get("/api/admin/overview").header("Authorization", "Bearer " + pendingToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOTP_VERIFICATION_REQUIRED"));

        String nextCode = totpService.generateCode(secret, Instant.now().plusSeconds(30));
        String verifiedRes = mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + pendingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + nextCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaState").value("VERIFIED"))
                .andReturn().getResponse().getContentAsString();
        String verifiedToken = objectMapper.readTree(verifiedRes).path("accessToken").asText();
        mvc.perform(get("/api/admin/overview").header("Authorization", "Bearer " + verifiedToken))
                .andExpect(status().isOk());
    }
}
