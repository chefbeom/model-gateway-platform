package com.aiconnect.llmgateway;

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
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:refreshrotation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefreshTokenRotationIntegrationTest {
    private static final String COOKIE = "aiconnect_refresh";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void refreshRotatesOnceAndLogoutRevokesTheReplacement() throws Exception {
        String credentials = "{\"email\":\"rotation@example.com\",\"password\":\"correct-horse-battery-staple\"}";
        MvcResult bootstrap = mvc.perform(post("/api/auth/bootstrap").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(COOKIE, true))
                .andExpect(cookie().secure(COOKIE, true))
                .andReturn();
        Cookie original = bootstrap.getResponse().getCookie(COOKIE);
        assertThat(original).isNotNull();
        assertThat(bootstrap.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("SameSite=Strict", "Path=/api/auth");

        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(original))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(COOKIE, true))
                .andReturn();
        Cookie replacement = refreshed.getResponse().getCookie(COOKIE);
        String accessToken = mapper.readTree(refreshed.getResponse().getContentAsString()).path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        mvc.perform(get("/api/portal/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
        assertThat(replacement).isNotNull();
        assertThat(replacement.getValue()).isNotEqualTo(original.getValue());

        mvc.perform(post("/api/auth/refresh").cookie(original))
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
