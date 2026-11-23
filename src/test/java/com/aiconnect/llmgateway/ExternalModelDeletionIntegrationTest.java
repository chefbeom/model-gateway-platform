package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.admin.AdminDtos;
import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:external_model_delete;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ExternalModelDeletionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired ExternalProviderRepository providers;
    @Autowired ModelDeploymentRepository models;
    @Autowired ProjectRepository projects;
    @Autowired ApiKeyService keys;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
    @Autowired LlmRequestRepository requests;
    @Autowired LlmRequestAttemptRepository attempts;
    @Autowired SecretCipher cipher;
    @Autowired ControlPlaneService control;

    @Test void providerAddressChangeRequiresNewCredential() throws Exception {
        var org = organizations.save(new Organization("Provider security " + UUID.randomUUID()));
        var provider = providers.save(new ExternalProvider(org.getId(), ExternalProviderType.OPENAI,
                "Provider", "https://provider.test/v1", cipher.encrypt("old-key")));
        String path = "/api/admin/external-providers/" + provider.getId();
        mvc.perform(patch(path).header("X-Admin-Token", "integration-admin-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"https://new-provider.test/v1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROVIDER_URL_REQUIRES_NEW_KEY"));
        mvc.perform(patch(path).header("X-Admin-Token", "integration-admin-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"https://new-provider.test/v1\",\"apiKey\":\"old-key\"}"))
                .andExpect(status().isBadRequest());
        assertThat(providers.findById(provider.getId()).orElseThrow().getBaseUrl()).isEqualTo("https://provider.test/v1");
        mvc.perform(patch(path).header("X-Admin-Token", "integration-admin-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"https://new-provider.test/v1\",\"apiKey\":\"new-key\"}"))
                .andExpect(status().isOk());
        assertThat(cipher.decrypt(providers.findById(provider.getId()).orElseThrow().getEncryptedApiKey())).isEqualTo("new-key");
    }

    @Test void deletesOnlyUnlinkedModelPreservesHistoryAndCanRestoreSameIdentity() throws Exception {
        var org=organizations.save(new Organization("Delete models " + UUID.randomUUID()));
        var provider=providers.save(new ExternalProvider(org.getId(),ExternalProviderType.OPENAI,"Provider","https://example.test/v1",cipher.encrypt("secret")));
        var other=providers.save(new ExternalProvider(org.getId(),ExternalProviderType.OPENAI,"Other","https://other.test/v1",cipher.encrypt("secret")));
        var model=models.save(ModelDeployment.external(provider.getId(),"wrong-model","wrong-model","Wrong",8192,20,"[]",BigDecimal.ONE,BigDecimal.ONE));
        var untouched=models.save(ModelDeployment.external(provider.getId(),"other-model","other-model","Keep",8192,20,"[]",BigDecimal.ONE,BigDecimal.ONE));
        var service=services.save(new LlmService(org.getId(),"delete-test","Service",FailoverPolicy.COMPATIBLE,false,"[]",BigDecimal.ZERO,BigDecimal.ZERO));
        var target=control.addTarget(service.getId(),new AdminDtos.CreateTarget(model.getId(),1,100,false,null));
        String path="/api/admin/external-providers/"+provider.getId()+"/models/"+model.getId();
        mvc.perform(get(path+"/deletion-preview").header("X-Admin-Token","integration-admin-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.targetCount").value(1));
        mvc.perform(delete(path).header("X-Admin-Token","integration-admin-token"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXTERNAL_MODEL_IN_USE"));
        assertThat(models.findById(model.getId()).orElseThrow().isArchived()).isFalse();
        targets.deleteById(target.getId());
        var project=projects.save(new Project(org.getId(),"Project"));
        var key=keys.issue(project.getId(),"key",null);
        var request=new LlmRequest(UUID.randomUUID().toString(),project.getId(),key.id(),null,service,false);
        request.succeed(model.getId(),100,20,500,200,0); request=requests.save(request);
        attempts.save(new LlmRequestAttempt(request.getId(),model.getId(),1));
        mvc.perform(delete("/api/admin/external-providers/{providerId}/models/{modelId}",other.getId(),model.getId())
                .header("X-Admin-Token","integration-admin-token")).andExpect(status().isNotFound());
        mvc.perform(delete(path)).andExpect(status().isUnauthorized());
        mvc.perform(delete(path).header("X-Admin-Token","integration-admin-token")).andExpect(status().isNoContent());
        assertThat(models.findById(model.getId()).orElseThrow().isArchived()).isTrue();
        assertThat(models.findById(model.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(requests.findById(request.getId()).orElseThrow().getFinalDeploymentId()).isEqualTo(model.getId());
        assertThat(attempts.existsByDeploymentIdIn(List.of(model.getId()))).isTrue();
        assertThat(providers.findById(provider.getId())).isPresent();assertThat(models.findById(untouched.getId()).orElseThrow().isArchived()).isFalse();
        mvc.perform(get("/api/admin/external-providers/{providerId}/models",provider.getId()).header("X-Admin-Token","integration-admin-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        String playground=mvc.perform(get("/api/admin/organizations/{organizationId}/playground/targets",org.getId())
                .header("X-Admin-Token","integration-admin-token")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(playground).doesNotContain(model.getId().toString()).contains(untouched.getId().toString());
        mvc.perform(patch(path).header("X-Admin-Token","integration-admin-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/admin/model-deployments/{id}",model.getId()).header("X-Admin-Token","integration-admin-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isNotFound());
        assertThatThrownBy(()->control.addTarget(service.getId(),new AdminDtos.CreateTarget(model.getId(),1,100,false,null)))
                .isInstanceOf(ApiException.class);
        mvc.perform(post("/api/admin/external-providers/{providerId}/models",provider.getId())
                .header("X-Admin-Token","integration-admin-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerModelId\":\"wrong-model\",\"displayName\":\"Restored\",\"maxConcurrency\":5,\"currency\":\"USD\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(model.getId().toString()));
        var restored=models.findById(model.getId()).orElseThrow();
        assertThat(restored.isArchived()).isFalse();assertThat(restored.isEnabled()).isTrue();
        assertThat(restored.getDisplayName()).isEqualTo("Restored");assertThat(restored.getMaxConcurrency()).isEqualTo(5);
        assertThat(requests.findById(request.getId()).orElseThrow().getFinalDeploymentId()).isEqualTo(restored.getId());
    }

    @Test void bulkRegistrationRestoresArchivedModelWithoutDuplicate() throws Exception {
        var org=organizations.save(new Organization("Bulk restore " + UUID.randomUUID()));
        var provider=providers.save(new ExternalProvider(org.getId(),ExternalProviderType.OPENAI,"Provider","https://bulk.test/v1",cipher.encrypt("secret")));
        var model=models.save(ModelDeployment.external(provider.getId(),"restore-me","restore-me","Old",8192,20,"[]",null,null));
        String path="/api/admin/external-providers/"+provider.getId();
        mvc.perform(delete(path+"/models/"+model.getId()).header("X-Admin-Token","integration-admin-token")).andExpect(status().isNoContent());
        mvc.perform(post(path+"/models/batch").header("X-Admin-Token","integration-admin-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"models\":[{\"providerModelId\":\"restore-me\",\"displayName\":\"New\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(model.getId().toString()));
        assertThat(models.findByExternalProviderId(provider.getId())).hasSize(1);
        assertThat(models.findById(model.getId()).orElseThrow().getDisplayName()).isEqualTo("New");
    }

    @Test void allowsMultipleProvidersWithSameBaseUrlAndDistinctDisplayNames() throws Exception {
        var org = organizations.save(new Organization("Shared URL org " + UUID.randomUUID()));
        String sharedUrl = "https://shared-openai.test/v1";

        mvc.perform(post("/api/admin/external-providers")
                        .header("X-Admin-Token", "integration-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + org.getId() + "\",\"displayName\":\"OpenAI Account A\",\"baseUrl\":\"" + sharedUrl + "\",\"apiKey\":\"sk-account-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value(sharedUrl));

        mvc.perform(post("/api/admin/external-providers")
                        .header("X-Admin-Token", "integration-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + org.getId() + "\",\"displayName\":\"OpenAI Account B\",\"baseUrl\":\"" + sharedUrl + "\",\"apiKey\":\"sk-account-b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value(sharedUrl));

        mvc.perform(post("/api/admin/external-providers")
                        .header("X-Admin-Token", "integration-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + org.getId() + "\",\"displayName\":\"openai account a\",\"baseUrl\":\"" + sharedUrl + "\",\"apiKey\":\"sk-account-c\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_NAME_EXISTS"));

        var third = providers.save(new ExternalProvider(org.getId(), ExternalProviderType.OPENAI,
                "OpenAI Account C", "https://other-provider.test/v1", cipher.encrypt("sk-old-c")));
        mvc.perform(patch("/api/admin/external-providers/{providerId}", third.getId())
                        .header("X-Admin-Token", "integration-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"OpenAI Account C\",\"baseUrl\":\"" + sharedUrl + "\",\"apiKey\":\"sk-new-c\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value(sharedUrl));

        assertThat(providers.findByOrganizationIdOrderByDisplayNameAsc(org.getId())).hasSize(3);
    }
}
