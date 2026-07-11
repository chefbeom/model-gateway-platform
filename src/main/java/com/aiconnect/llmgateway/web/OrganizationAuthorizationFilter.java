package com.aiconnect.llmgateway.web;

import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.OrganizationMember;
import com.aiconnect.llmgateway.identity.OrganizationMemberRepository;
import com.aiconnect.llmgateway.identity.OrganizationRole;
import com.aiconnect.llmgateway.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Component
public class OrganizationAuthorizationFilter extends OncePerRequestFilter {
    private final ObjectMapper objectMapper;
    private final OrganizationMemberRepository members;
    private final ProjectRepository projects;
    private final InferenceNodeRepository nodes;
    private final RuntimeEndpointRepository endpoints;
    private final ModelDeploymentRepository deployments;
    private final LlmServiceRepository services;
    private final ApiKeyRepository apiKeys;

    public OrganizationAuthorizationFilter(ObjectMapper objectMapper, OrganizationMemberRepository members, ProjectRepository projects,
                                           InferenceNodeRepository nodes, RuntimeEndpointRepository endpoints,
                                           ModelDeploymentRepository deployments, LlmServiceRepository services,
                                           ApiKeyRepository apiKeys) {
        this.objectMapper = objectMapper; this.members = members; this.projects = projects; this.nodes = nodes;
        this.endpoints = endpoints; this.deployments = deployments; this.services = services; this.apiKeys = apiKeys;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/admin/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        if (Boolean.TRUE.equals(request.getAttribute("aiconnect.platform-admin"))) { filterChain.doFilter(request, response); return; }
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof AuthPrincipal actor)) { deny(response, "ADMIN_AUTH_REQUIRED", "An authenticated administrator is required."); return; }
        if (actor.platformAdmin()) { filterChain.doFilter(request, response); return; }
        if ("GET".equals(request.getMethod()) && "/api/admin/organizations".equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        UUID organizationId = organizationFor(request.getMethod(), request.getRequestURI(), parse(body));
        if (organizationId == null) { deny(response, "ORGANIZATION_SCOPE_REQUIRED", "This operation requires platform-administrator access or an organization scope."); return; }
        Optional<OrganizationMember> membership = members.findByIdOrganizationIdAndIdUserId(organizationId, actor.userId());
        if (membership.isEmpty() || membership.get().getRole() != OrganizationRole.ORGANIZATION_ADMIN) { deny(response, "ORGANIZATION_ADMIN_REQUIRED", "An organization administrator is required for this operation."); return; }
        filterChain.doFilter(new BufferedBodyRequest(request, body), response);
    }
    private UUID organizationFor(String method, String uri, JsonNode body) {
        String[] parts = uri.split("/");
        try {
            if (uri.startsWith("/api/admin/organizations/") && parts.length >= 5) return UUID.fromString(parts[4]);
            if (uri.startsWith("/api/admin/projects/") && parts.length >= 5) return projectOrganization(UUID.fromString(parts[4]));
            if (uri.startsWith("/api/admin/nodes/") && parts.length >= 5) return nodeOrganization(UUID.fromString(parts[4]));
            if (uri.startsWith("/api/admin/services/") && parts.length >= 5) return services.findById(UUID.fromString(parts[4])).map(LlmService::getOrganizationId).orElse(null);
            if (uri.startsWith("/api/admin/runtime-endpoints/") && parts.length >= 5) return endpointOrganization(UUID.fromString(parts[4]));
            if (uri.startsWith("/api/admin/model-deployments/") && parts.length >= 5) return deploymentOrganization(UUID.fromString(parts[4]));
            if (uri.startsWith("/api/admin/api-keys/") && parts.length >= 5) return apiKeys.findById(UUID.fromString(parts[4])).map(key -> projectOrganization(key.getProjectId())).orElse(null);
            if (!"POST".equals(method)) return null;
            if (uri.equals("/api/admin/projects") || uri.equals("/api/admin/nodes") || uri.equals("/api/admin/services")) return uuid(body, "organizationId");
            if (uri.equals("/api/admin/runtime-endpoints")) return nodeOrganization(uuid(body, "nodeId"));
            if (uri.equals("/api/admin/model-deployments")) return endpointOrganization(uuid(body, "runtimeEndpointId"));
            return null;
        } catch (Exception ignored) { return null; }
    }
    private UUID projectOrganization(UUID projectId) { return projects.findById(projectId).map(Project::getOrganizationId).orElse(null); }
    private UUID nodeOrganization(UUID nodeId) { return nodes.findById(nodeId).map(InferenceNode::getOrganizationId).orElse(null); }
    private UUID endpointOrganization(UUID endpointId) { return endpoints.findById(endpointId).map(endpoint -> nodeOrganization(endpoint.getNodeId())).orElse(null); }
    private UUID deploymentOrganization(UUID deploymentId) { return deployments.findById(deploymentId).map(deployment -> endpointOrganization(deployment.getRuntimeEndpointId())).orElse(null); }
    private UUID uuid(JsonNode body, String field) { return body != null && body.hasNonNull(field) ? UUID.fromString(body.get(field).asText()) : null; }
    private JsonNode parse(byte[] body) { try { return body.length == 0 ? null : objectMapper.readTree(body); } catch (Exception ignored) { return null; } }
    private void deny(HttpServletResponse response, String code, String message) throws IOException { response.setStatus(HttpStatus.FORBIDDEN.value()); response.setContentType("application/json"); response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}"); }
}
