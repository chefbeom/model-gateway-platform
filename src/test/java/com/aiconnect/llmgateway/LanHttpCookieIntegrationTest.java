package com.aiconnect.llmgateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lancookie;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "AUTH_COOKIE_SECURE=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LanHttpCookieIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void trustedLanHttpModeIssuesNonSecureHttpOnlyRefreshCookie() throws Exception {
        String credentials = "{\"email\":\"lan-admin@example.com\",\"password\":\"correct-horse-battery-staple\"}";
        mvc.perform(post("/api/auth/bootstrap").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("aiconnect_refresh", true))
                .andExpect(cookie().secure("aiconnect_refresh", false));
    }
}
