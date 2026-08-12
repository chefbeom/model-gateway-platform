package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.ExternalProviderType;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.repository.ExternalProviderRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.service.SecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:aiconnect_external_model_pricing;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ExternalProviderModelPricingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired ExternalProviderRepository providers;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired SecretCipher cipher;

    @Test
    void updatesRegisteredExternalModelPricingAndCurrency() throws Exception {
        Organization organization = organizations.save(new Organization("External model pricing"));
        ExternalProvider provider = providers.save(new ExternalProvider(organization.getId(), ExternalProviderType.OPENAI,
                "OpenAI Pricing", "https://api.openai.com/v1", cipher.encrypt("provider-secret")));
        ModelDeployment model = deployments.save(ModelDeployment.external(provider.getId(), "gpt-external",
                "text-compatible", "External Model", 128000, 20, "[\"CHAT_COMPLETION\"]",
                new BigDecimal("0.2"), new BigDecimal("1.2"), Currency.KRW));

        mvc.perform(patch("/api/admin/external-providers/{providerId}/models/{modelId}", provider.getId(), model.getId())
                        .header("X-Admin-Token", "integration-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"External Model Updated\",\"compatibilityKey\":\"text-updated\","
                                + "\"contextLength\":64000,\"maxConcurrency\":4,\"inputPricePerMillion\":0.45,"
                                + "\"outputPricePerMillion\":1.75,\"currency\":\"USD\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("External Model Updated"))
                .andExpect(jsonPath("$.inputPricePerMillion").value(0.45))
                .andExpect(jsonPath("$.outputPricePerMillion").value(1.75))
                .andExpect(jsonPath("$.currency").value("USD"));

        ModelDeployment saved = deployments.findById(model.getId()).orElseThrow();
        assertThat(saved.getDisplayName()).isEqualTo("External Model Updated");
        assertThat(saved.getCompatibilityKey()).isEqualTo("text-updated");
        assertThat(saved.getProviderInputPricePerMillion()).isEqualByComparingTo("0.45");
        assertThat(saved.getProviderOutputPricePerMillion()).isEqualByComparingTo("1.75");
        assertThat(saved.getProviderPriceCurrency()).isEqualTo(Currency.USD);
    }
}
