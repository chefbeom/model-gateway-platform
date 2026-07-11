package com.aiconnect.llmgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class IdentityWorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void bootstrapLoginAndPlatformAdminAccessWorkTogether() throws Exception {
        String body = "{\"email\":\"owner@example.com\",\"password\":\"correct-horse-battery-staple\"}";
        String bootstrap = mvc.perform(post("/api/auth/bootstrap").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly("aiconnect_refresh", true)).andReturn().getResponse().getContentAsString();
        JsonNode issued = objectMapper.readTree(bootstrap);
        String token = issued.path("accessToken").asText();
        assertThat(token).isNotBlank();

        mvc.perform(get("/api/admin/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly("aiconnect_refresh", true));
    }
}
