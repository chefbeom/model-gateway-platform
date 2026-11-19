package com.aiconnect.llmgateway.beta;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:beta_invitation_integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "AICONNECT_BETA_INGRESS_TOKEN=integration-beta-ingress-token-0123456789abcdef"
})
class BetaInvitationIntegrationTest {
 private static final String BETA_INGRESS_HEADER = "X-AIConnect-Beta-Ingress";
 private static final String BETA_INGRESS_TOKEN = "integration-beta-ingress-token-0123456789abcdef";
 @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired BetaAdministration admin; @Autowired BetaAccessService guard;
 @Autowired BetaInvitationRepository invitations; @Autowired BetaMemberRepository members;
 @Autowired OrganizationRepository organizations; @Autowired LlmServiceRepository services; @Autowired ProjectRepository projects;
 @Autowired ProjectServiceAccessRepository access; @Autowired ApiKeyService keys;
 @Autowired com.aiconnect.llmgateway.identity.IdentityService identities;
 @Autowired com.aiconnect.llmgateway.scheduling.SchedulingPolicyService scheduling;
 @Autowired com.aiconnect.llmgateway.retention.RequestContentService requestContent;

 @Test void oneUseNormalizedInvitationCreatesIsolatedLocalOnlyProjectAndCanBeSuspended() throws Exception {
  var org=organizations.save(new Organization("Beta integration "+UUID.randomUUID()));
  var existing=projects.save(new Project(org.getId(),"Existing production project"));
  var model=services.save(new LlmService(org.getId(),"beta-"+UUID.randomUUID(),"Beta",FailoverPolicy.COMPATIBLE,false,"[]",BigDecimal.ZERO,BigDecimal.ZERO));
  var unrelated=services.save(new LlmService(org.getId(),"other-"+UUID.randomUUID(),"Other",FailoverPolicy.COMPATIBLE,false,"[]",BigDecimal.ZERO,BigDecimal.ZERO));
  var service=admin.create(org.getId(),model.getId());assertThat(service.getMode()).isEqualTo("PAUSED");
  var invite=admin.invite(service.getId(),"User@Example.Invalid",Instant.now().plusSeconds(3600));
  var redeem=mapper.createObjectNode().put("email","user@example.invalid").put("token",invite.token());
  mvc.perform(post("/api/beta/invitations/redeem").contentType(MediaType.APPLICATION_JSON).content(redeem.toString())).andExpect(status().isNotFound());
  var issued=admin.redeem("user@example.invalid",invite.token());var secret=issued.secret();assertThat(secret).startsWith("sk_llmg_");
  var member=members.findByServiceId(service.getId()).get(0);var project=projects.findById(member.getProjectId()).orElseThrow();
  assertThat(project.getId()).isNotEqualTo(existing.getId());assertThat(project.isExternalAiBlocked()).isTrue();
  assertThat(issued.expiresAt()).isAfter(Instant.now());
  assertThat(access.existsByIdProjectIdAndIdServiceId(project.getId(),model.getId())).isTrue();
  assertThat(access.existsByIdProjectIdAndIdServiceId(project.getId(),unrelated.getId())).isFalse();
  assertThat(access.findByIdProjectId(project.getId())).hasSize(1);
  assertThat(projects.findById(existing.getId()).orElseThrow().isExternalAiBlocked()).isFalse();
  assertThatThrownBy(()->guard.checkProject(project.getId(),model.getId())).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->admin.mode(service.getId(),"LOCAL_ONLY")).isInstanceOf(ApiException.class);
  scheduling.save(model.getId(),new com.aiconnect.llmgateway.scheduling.SchedulingSettings(com.aiconnect.llmgateway.scheduling.SchedulingMode.LOCAL_ONLY_QUEUE,0,5000,10,1024*1024,2));
  admin.mode(service.getId(),"LOCAL_ONLY");guard.checkProject(project.getId(),model.getId());
  assertThatThrownBy(()->guard.checkProject(project.getId(),unrelated.getId())).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->admin.redeem("user@example.invalid",invite.token())).isInstanceOf(ApiException.class);
  var credentials=keys.authenticate("Bearer "+secret);admin.suspend(service.getId(),member.getId());
  assertThatThrownBy(()->guard.requirePublicBetaProject(project.getId())).isInstanceOf(ApiException.class);
  keys.revoke(credentials.apiKey().getId());assertThatThrownBy(()->keys.authenticate("Bearer "+secret)).isInstanceOf(ApiException.class);
 }

 @Test void revokedExpiredAndMismatchedInvitationsCannotProvisionProject() {
  var org=organizations.save(new Organization("Beta expiration "+UUID.randomUUID()));
  var model=services.save(new LlmService(org.getId(),"beta-"+UUID.randomUUID(),"Beta",FailoverPolicy.COMPATIBLE,false,"[]",BigDecimal.ZERO,BigDecimal.ZERO));
  var service=admin.create(org.getId(),model.getId());long before=projects.count();
  var revoked=admin.invite(service.getId(),"a@example.invalid",Instant.now().plusSeconds(3600));admin.revokeInvitation(service.getId(),revoked.id());
  assertThatThrownBy(()->admin.redeem("a@example.invalid",revoked.token())).isInstanceOf(ApiException.class);
  var expired=admin.invite(service.getId(),"b@example.invalid",Instant.now().plusSeconds(3600));
  var record=invitations.findById(expired.id()).orElseThrow();record.expiresAt=Instant.now().minusSeconds(1);invitations.saveAndFlush(record);
  assertThatThrownBy(()->admin.redeem("b@example.invalid",expired.token())).isInstanceOf(ApiException.class);
  var valid=admin.invite(service.getId(),"c@example.invalid",Instant.now().plusSeconds(3600));
  assertThatThrownBy(()->admin.redeem("different@example.invalid",valid.token())).isInstanceOf(ApiException.class);
  assertThat(projects.count()).isEqualTo(before);
 }

 @Test void assignedOperatorIsScopedToOnlyItsOwnBetaService() {
  var org=organizations.save(new Organization("Beta operator "+UUID.randomUUID()));
  var firstModel=services.save(new LlmService(org.getId(),"first-"+UUID.randomUUID(),"First",FailoverPolicy.COMPATIBLE,false,"[]",BigDecimal.ZERO,BigDecimal.ZERO));
  var secondModel=services.save(new LlmService(org.getId(),"second-"+UUID.randomUUID(),"Second",FailoverPolicy.COMPATIBLE,false,"[]",BigDecimal.ZERO,BigDecimal.ZERO));
  var first=admin.create(org.getId(),firstModel.getId());var second=admin.create(org.getId(),secondModel.getId());
  var operator=identities.createUser(UUID.randomUUID()+"@example.invalid","long-test-password",false);
  admin.assign(first.getId(),operator.getId());
  assertThat(admin.canOperate(first.getId(),operator.getId())).isTrue();
  assertThat(admin.canOperate(second.getId(),operator.getId())).isFalse();
  assertThat(admin.list(operator.getId(),false)).extracting(BetaService::getId).containsExactly(first.getId());
 }

 @Test void publicIngressRedeemModelsAndMetadataOnlyRetentionAreEnforced() throws Exception {
  var org=organizations.save(new Organization("Beta ingress "+UUID.randomUUID()));
  var existing=projects.save(new Project(org.getId(),"Existing production project"));
  var existingKey=keys.issue(existing.getId(),"production",null);
  var model=services.save(new LlmService(org.getId(),"beta-model-"+UUID.randomUUID(),"Beta Model",FailoverPolicy.COMPATIBLE,false,"[]",BigDecimal.ZERO,BigDecimal.ZERO));
  var unrelated=services.save(new LlmService(org.getId(),"other-model-"+UUID.randomUUID(),"Other Model",FailoverPolicy.COMPATIBLE,false,"[]",BigDecimal.ZERO,BigDecimal.ZERO));
  access.save(new ProjectServiceAccess(existing.getId(),model.getId()));
  access.save(new ProjectServiceAccess(existing.getId(),unrelated.getId()));
  scheduling.save(model.getId(),new com.aiconnect.llmgateway.scheduling.SchedulingSettings(com.aiconnect.llmgateway.scheduling.SchedulingMode.LOCAL_ONLY_QUEUE,0,5000,10,1024*1024,2));
  var service=admin.create(org.getId(),model.getId());
  var invite=admin.invite(service.getId(),"IngressUser@Example.Invalid",Instant.now().plusSeconds(3600));
  var redeemBody=mapper.createObjectNode().put("email","ingressuser@example.invalid").put("token",invite.token()).toString();

  mvc.perform(post("/api/beta/invitations/redeem").contentType(MediaType.APPLICATION_JSON).content(redeemBody))
          .andExpect(status().isNotFound());
  mvc.perform(post("/api/beta/invitations/redeem").header(BETA_INGRESS_HEADER,"invalid-token").contentType(MediaType.APPLICATION_JSON).content(redeemBody))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error.code").value("BETA_INGRESS_DENIED"));

  var redeemedResult=mvc.perform(post("/api/beta/invitations/redeem")
                  .header(BETA_INGRESS_HEADER,BETA_INGRESS_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(redeemBody))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control",containsString("no-store")))
          .andReturn();
  var redeemedJson=mapper.readTree(redeemedResult.getResponse().getContentAsString());
  String betaSecret=redeemedJson.path("secret").asText();
  assertThat(betaSecret).startsWith("sk_llmg_");
  assertThat(redeemedJson.path("expiresAt").asText()).isNotBlank();

  mvc.perform(post("/api/beta/invitations/redeem")
                  .header(BETA_INGRESS_HEADER,BETA_INGRESS_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(redeemBody))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("BETA_INVITATION_INVALID"));

  var member=members.findByServiceId(service.getId()).get(0);
  UUID betaProjectId=member.getProjectId();

  mvc.perform(get("/v1/models").header(BETA_INGRESS_HEADER,BETA_INGRESS_TOKEN).header("Authorization","Bearer "+betaSecret))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error.code").value("BETA_ACCESS_DISABLED"));

  admin.mode(service.getId(),"LOCAL_ONLY");

  mvc.perform(get("/v1/models").header(BETA_INGRESS_HEADER,BETA_INGRESS_TOKEN).header("Authorization","Bearer "+existingKey.secret()))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error.code").value("BETA_KEY_REQUIRED"));

  mvc.perform(get("/v1/models").header(BETA_INGRESS_HEADER,BETA_INGRESS_TOKEN).header("Authorization","Bearer "+betaSecret))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.object").value("list"))
          .andExpect(jsonPath("$.data.length()").value(1))
          .andExpect(jsonPath("$.data[0].id").value(model.getServiceKey()));

  mvc.perform(get("/v1/models").header("Authorization","Bearer "+existingKey.secret()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(2));

  assertThatThrownBy(()->requestContent.setPolicy(betaProjectId,com.aiconnect.llmgateway.retention.ContentRetentionMode.FULL_ENCRYPTED))
          .isInstanceOf(ApiException.class)
          .satisfies(error->{
           var api=(ApiException)error;
           assertThat(api.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
           assertThat(api.getCode()).isEqualTo("BETA_CONTENT_RETENTION_FORBIDDEN");
          });
  assertThat(requestContent.mode(betaProjectId)).isEqualTo(com.aiconnect.llmgateway.retention.ContentRetentionMode.METADATA_ONLY);
 }
}
