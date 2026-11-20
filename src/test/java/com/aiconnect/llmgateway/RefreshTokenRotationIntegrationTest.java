package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.identity.TotpService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:refreshrotation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefreshTokenRotationIntegrationTest {
    private static final String COOKIE = "aiconnect_refresh";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TotpService totpService;

    @Test
    void refreshRotatesOncePreservesMfaStateAndLogoutRevokesTheReplacement() throws Exception {
        String credentials = "{\"email\":\"rotation@example.com\",\"password\":\"correct-horse-battery-staple\"}";
        MvcResult bootstrap = mvc.perform(post("/api/auth/bootstrap").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(COOKIE, true))
                .andExpect(cookie().secure(COOKIE, true))
                .andExpect(jsonPath("$.mfaState").value("ENROLLMENT_REQUIRED"))
                .andReturn();
        Cookie original = bootstrap.getResponse().getCookie(COOKIE);
        assertThat(original).isNotNull();
        assertThat(bootstrap.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("SameSite=Strict", "Path=/api/auth");

        MvcResult preMfaRefreshed = mvc.perform(post("/api/auth/refresh").cookie(original))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaState").value("ENROLLMENT_REQUIRED"))
                .andReturn();
        Cookie preMfaCookie = preMfaRefreshed.getResponse().getCookie(COOKIE);
        String preMfaAccess = mapper.readTree(preMfaRefreshed.getResponse().getContentAsString()).path("accessToken").asText();
        mvc.perform(get("/api/portal/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + preMfaAccess))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOTP_ENROLLMENT_REQUIRED"));

        String setupJson = mvc.perform(post("/api/auth/totp/setup").header(HttpHeaders.AUTHORIZATION, "Bearer " + preMfaAccess))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = mapper.readTree(setupJson).path("secret").asText();
        String code = totpService.generateCode(secret, Instant.now());

        MvcResult enrolled = mvc.perform(post("/api/auth/totp/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + preMfaAccess)
                        .cookie(preMfaCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.mfaState").value("VERIFIED"))
                .andReturn();
        Cookie verifiedCookie = enrolled.getResponse().getCookie(COOKIE);
        assertThat(verifiedCookie).isNotNull();

        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(verifiedCookie))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(COOKIE, true))
                .andExpect(jsonPath("$.mfaState").value("VERIFIED"))
                .andReturn();
        Cookie replacement = refreshed.getResponse().getCookie(COOKIE);
        JsonNode refreshedJson = mapper.readTree(refreshed.getResponse().getContentAsString());
        String accessToken = refreshedJson.path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        mvc.perform(get("/api/portal/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
        assertThat(replacement).isNotNull();
        assertThat(replacement.getValue()).isNotEqualTo(verifiedCookie.getValue());

        mvc.perform(post("/api/auth/refresh").cookie(verifiedCookie))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/logout").cookie(replacement))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(COOKIE, 0));

        mvc.perform(get("/api/portal/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/refresh").cookie(replacement))
                .andExpect(status().isUnauthorized());
    }
}
