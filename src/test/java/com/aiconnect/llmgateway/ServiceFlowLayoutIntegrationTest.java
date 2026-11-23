package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.flowdesigner.ServiceFlowLayoutRepository;
import com.aiconnect.llmgateway.identity.IdentityService;
import com.aiconnect.llmgateway.identity.OrganizationRole;
import com.aiconnect.llmgateway.identity.TotpService;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_flow_layout;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ServiceFlowLayoutIntegrationTest {
    private static final String ADMIN = "integration-admin-token";
    private static final String PW = "correct-horse-battery-staple";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired IdentityService identity;
    @Autowired TotpService totpService;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceFlowLayoutRepository layouts;

    private LlmService newService(Organization org, String key) {
        return services.save(new LlmService(org.getId(), key, key, FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]",
                BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private String url(UUID serviceId) { return "/api/admin/services/" + serviceId + "/flow-layout"; }

    private String body(Map<String, Object> nodes) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 1);
        m.put("viewport", Map.of("x", 10, "y", -20, "zoom", 1.5));
        m.put("nodes", nodes);
        return objectMapper.writeValueAsString(m);
    }

    private void putExpectInvalid(UUID id, String json, String code) throws Exception {
        mvc.perform(put(url(id)).header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void platformAdminReadsAndSavesLayoutWithRevisions() throws Exception {
        Organization org = organizations.save(new Organization("Flow Org 1"));
        LlmService svc = newService(org, "flow-1");
        mvc.perform(get(url(svc.getId())).header("X-Admin-Token", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.layout").doesNotExist());

        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("target:abc-1", Map.of("x", 1.5, "y", 2.5, "collapsed", false));
        nodes.put("service", Map.of("x", 0, "y", 0, "collapsed", true));
        mvc.perform(put(url(svc.getId())).header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON).content(body(nodes)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get(url(svc.getId())).header("X-Admin-Token", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.layout.version").value(1))
                .andExpect(jsonPath("$.layout.viewport.zoom").value(1.5))
                .andExpect(jsonPath("$.layout.nodes['target:abc-1'].x").value(1.5))
                .andExpect(jsonPath("$.layout.nodes['target:abc-1'].collapsed").doesNotExist())
                .andExpect(jsonPath("$.layout.nodes.service.collapsed").value(true));
        mvc.perform(put(url(svc.getId())).header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON).content(body(nodes)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    void rejectsInvalidAndOversizedLayouts() throws Exception {
        Organization org = organizations.save(new Organization("Flow Org 2"));
        UUID id = newService(org, "flow-2").getId();
        putExpectInvalid(id, body(Map.of("evil", Map.of("x", 1, "y", 1))), "FLOW_LAYOUT_INVALID");
        putExpectInvalid(id, body(Map.of("target:../x", Map.of("x", 1, "y", 1))), "FLOW_LAYOUT_INVALID");
        putExpectInvalid(id, body(Map.of("service", Map.of("x", 1e9, "y", 1))), "FLOW_LAYOUT_INVALID");
        putExpectInvalid(id, "{\"version\":1,\"viewport\":{\"x\":0,\"y\":0,\"zoom\":10},\"nodes\":{}}", "FLOW_LAYOUT_INVALID");
        putExpectInvalid(id, "{\"version\":2,\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1},\"nodes\":{}}", "FLOW_LAYOUT_INVALID");
        putExpectInvalid(id, "{\"version\":1,\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}", "FLOW_LAYOUT_INVALID");

        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 501; i++) many.put("project:p" + i, Map.of("x", 1, "y", 1));
        putExpectInvalid(id, body(many), "FLOW_LAYOUT_INVALID");

        Map<String, Object> big = new LinkedHashMap<>();
        String suffix = "a".repeat(55);
        for (int i = 0; i < 500; i++) {
            big.put(String.format("project:%09d%s", i, suffix), Map.of("x", 123456.78901234567, "y", 123456.78901234567, "collapsed", true));
        }
        putExpectInvalid(id, body(big), "FLOW_LAYOUT_TOO_LARGE");
    }

    @Test
    void dropsUnknownFields() throws Exception {
        Organization org = organizations.save(new Organization("Flow Org 3"));
        UUID id = newService(org, "flow-3").getId();
        String json = "{\"version\":1,\"secret\":\"x\",\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1,\"secret\":\"x\"},"
                + "\"nodes\":{\"router\":{\"x\":1,\"y\":2,\"secret\":\"x\"}}}";
        String putResponse = mvc.perform(put(url(id)).header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String getResponse = mvc.perform(get(url(id)).header("X-Admin-Token", ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(putResponse).doesNotContain("secret");
        assertThat(getResponse).doesNotContain("secret");
        assertThat(layouts.findById(id).orElseThrow().getLayoutJson()).doesNotContain("secret");
    }

    @Test
    void tombstoneAndUnknownServicesAreNotServed() throws Exception {
        Organization org = organizations.save(new Organization("Flow Org 4"));
        LlmService svc = newService(org, "flow-4");
        svc.markDeleted();
        services.save(svc);
        mvc.perform(get(url(svc.getId())).header("X-Admin-Token", ADMIN))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
        int unknown = mvc.perform(get(url(UUID.randomUUID())).header("X-Admin-Token", ADMIN)).andReturn().getResponse().getStatus();
        assertThat(unknown).isIn(403, 404);
    }

    @Test
    void nonPlatformMembersAreDenied() throws Exception {
        Organization a = organizations.save(new Organization("Flow Org A"));
        Organization b = organizations.save(new Organization("Flow Org B"));
        LlmService serviceA = newService(a, "flow-a");
        LlmService serviceB = newService(b, "flow-b");
        String adminToken = token("flow-admin@example.com", a, OrganizationRole.ORGANIZATION_ADMIN);
        String devToken = token("flow-dev@example.com", a, OrganizationRole.DEVELOPER);

        mvc.perform(get(url(serviceA.getId())).header("Authorization", "Bearer " + adminToken)).andExpect(status().isForbidden());
        mvc.perform(put(url(serviceA.getId())).header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of()))).andExpect(status().isForbidden());
        mvc.perform(get(url(serviceB.getId())).header("Authorization", "Bearer " + devToken)).andExpect(status().isForbidden());
        mvc.perform(put(url(serviceB.getId())).header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of()))).andExpect(status().isForbidden());
        assertThat(layouts.findById(serviceA.getId())).isEmpty();
        assertThat(layouts.findById(serviceB.getId())).isEmpty();
    }

    @Test
    void hardDeletingServiceDoesNotFail() throws Exception {
        Organization org = organizations.save(new Organization("Flow Org 7"));
        LlmService svc = newService(org, "flow-7");
        mvc.perform(put(url(svc.getId())).header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("service", Map.of("x", 1, "y", 1))))).andExpect(status().isOk());
        services.deleteById(svc.getId());
        assertThat(services.findById(svc.getId())).isEmpty();
    }

    @Test
    void authenticatedPlatformAdminListsProjectsManagesServiceAccessAndSavesLayout() throws Exception {
        Organization org = organizations.save(new Organization("Flow Auth Org " + UUID.randomUUID()));
        Project project = projects.save(new Project(org.getId(), "flow-access-proj"));
        LlmService svc = newService(org, "flow-access-svc");
        SessionAccount admin = login("flow-sysadmin@example.com", org, OrganizationRole.ORGANIZATION_ADMIN, true);

        mvc.perform(get("/api/admin/organizations/{orgId}/projects", org.getId())
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(project.getId().toString()));

        mvc.perform(get("/api/admin/projects/{projectId}/service-access", project.getId())
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/admin/projects/{projectId}/service-access", project.getId())
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"" + svc.getId() + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/projects/{projectId}/service-access", project.getId())
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(svc.getId().toString()));

        mvc.perform(put(url(svc.getId()))
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("project:" + project.getId(), Map.of("x", 48, "y", 160)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.layout.nodes['project:" + project.getId() + "'].x").value(48));
        assertThat(layouts.findById(svc.getId()).orElseThrow().getUpdatedBy()).isEqualTo(admin.userId());

        mvc.perform(delete("/api/admin/projects/{projectId}/service-access/{serviceId}", project.getId(), svc.getId())
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/admin/projects/{projectId}/service-access", project.getId())
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private record SessionAccount(UUID userId, String token) { }

    private String token(String email, Organization org, OrganizationRole role) throws Exception {
        return login(email, org, role, false).token();
    }

    private SessionAccount login(String email, Organization org, OrganizationRole role, boolean platformAdmin) throws Exception {
        var user = identity.createUser(email, PW, platformAdmin);
        identity.grantMembership(org.getId(), user.getId(), role);
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String preMfa = objectMapper.readTree(login).path("accessToken").asText();
        String setupJson = mvc.perform(post("/api/auth/totp/setup").header("Authorization", "Bearer " + preMfa))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = objectMapper.readTree(setupJson).path("secret").asText();
        String code = totpService.generateCode(secret, Instant.now());
        MvcResult enroll = mvc.perform(post("/api/auth/totp/enroll").header("Authorization", "Bearer " + preMfa)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return new SessionAccount(user.getId(), objectMapper.readTree(enroll.getResponse().getContentAsString()).path("session").path("accessToken").asText());
    }
}
