package com.aiconnect.llmgateway.web;

import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class OrganizationAuthorizationFilter extends OncePerRequestFilter {
    private final ObjectMapper objectMapper;
    private final ProjectRepository projects;
    private final InferenceNodeRepository nodes;
    private final RuntimeEndpointRepository endpoints;
    private final ModelDeploymentRepository deployments;
    private final LlmServiceRepository services;
    private final ApiKeyRepository apiKeys;
    private final TeamAccessService access;

    public OrganizationAuthorizationFilter(ObjectMapper objectMapper, ProjectRepository projects, InferenceNodeRepository nodes,
                                           RuntimeEndpointRepository endpoints, ModelDeploymentRepository deployments,
                                           LlmServiceRepository services, ApiKeyRepository apiKeys, TeamAccessService access) {
        this.objectMapper = objectMapper;
        this.projects = projects;
        this.nodes = nodes;
        this.endpoints = endpoints;
        this.deployments = deployments;
        this.services = services;
        this.apiKeys = apiKeys;
        this.access = access;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {
        if (Boolean.TRUE.equals(request.getAttribute("aiconnect.platform-admin"))) {
            byte[] body = request.getInputStream().readAllBytes();
            UUID organizationId = organizationFor(request.getMethod(), request.getRequestURI(), parse(body));
            if (organizationId != null) request.setAttribute("aiconnect.organization-id", organizationId);
            filterChain.doFilter(new BufferedBodyRequest(request, body), response);
            return;
        }
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (actor == null) {
            deny(response, "ADMIN_AUTH_REQUIRED", "An authenticated administrator is required.");
            return;
        }
        if (actor.platformAdmin()) {
            filterChain.doFilter(request, response);
            return;
        }
        String uri = request.getRequestURI();
        if ("GET".equals(request.getMethod()) && "/api/admin/organizations".equals(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();
        JsonNode parsed = parse(body);
        UUID organizationId = organizationFor(request.getMethod(), uri, parsed);
        if (organizationId != null) request.setAttribute("aiconnect.organization-id", organizationId);
        if (organizationId == null || !access.canViewOrganization(actor, organizationId)) {
            deny(response, "ORGANIZATION_SCOPE_REQUIRED", "This operation requires membership in the organization.");
            return;
        }
        if (access.isOrganizationAdmin(actor, organizationId)) {
            filterChain.doFilter(new BufferedBodyRequest(request, body), response);
            return;
        }
        if ("GET".equals(request.getMethod()) && isOrganizationDiscoveryPath(uri)) {
            filterChain.doFilter(new BufferedBodyRequest(request, body), response);
            return;
        }
        if (isProjectCreate(request.getMethod(), uri)) {
            if (access.canCreateProject(actor, organizationId, uuid(parsed, "teamId"))) {
                filterChain.doFilter(new BufferedBodyRequest(request, body), response);
            } else {
                deny(response, "PROJECT_OWNER_REQUIRED", "A team administrator or project owner is required to create a project.");
            }
            return;
        }
        UUID projectId = projectFor(uri);
        if (projectId != null) {
            boolean allowed;
            if (!"GET".equals(request.getMethod())) allowed = access.canManageProject(actor, projectId);
            else if (isSensitiveContentPath(uri)) allowed = access.canReadSensitiveContent(actor, projectId);
            else allowed = access.canViewProject(actor, projectId);
            if (allowed) {
                filterChain.doFilter(new BufferedBodyRequest(request, body), response);
            } else {
                deny(response, isSensitiveContentPath(uri) ? "SENSITIVE_CONTENT_ACCESS_DENIED" : "PROJECT_ACCESS_DENIED",
                        "The current role cannot access this project resource.");
            }
            return;
        }
        UUID teamId = teamFor(uri);
        if (teamId != null && "GET".equals(request.getMethod()) && !uri.endsWith("/deletion-preview")) {
            filterChain.doFilter(new BufferedBodyRequest(request, body), response);
            return;
        }
        if (teamId != null && access.canManageTeam(actor, teamId)) {
            filterChain.doFilter(new BufferedBodyRequest(request, body), response);
            return;
        }
        deny(response, "ORGANIZATION_ADMIN_REQUIRED", "An organization administrator is required for this operation.");
    }

    private boolean isProjectCreate(String method, String uri) {
        return "POST".equals(method) && "/api/admin/projects".equals(uri);
    }

    private boolean isSensitiveContentPath(String uri) {
        return uri.matches("/api/admin/projects/[0-9a-fA-F-]+/requests/[^/]+/content");
    }

    private boolean isOrganizationDiscoveryPath(String uri) {
        return uri.matches("/api/admin/organizations/[0-9a-fA-F-]+/(projects|teams)(/[^/]+/members)?");
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
        } catch (Exception ignored) {
            return null;
        }
    }

    private UUID projectFor(String uri) {
        if (!uri.startsWith("/api/admin/projects/")) return null;
        String[] parts = uri.split("/");
        try {
            return parts.length >= 5 ? UUID.fromString(parts[4]) : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private UUID teamFor(String uri) {
        String[] parts = uri.split("/");
        try {
            return uri.startsWith("/api/admin/organizations/") && parts.length >= 7 && "teams".equals(parts[5])
                    ? UUID.fromString(parts[6]) : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private UUID projectOrganization(UUID projectId) {
        return projects.findById(projectId).map(Project::getOrganizationId).orElse(null);
    }

    private UUID nodeOrganization(UUID nodeId) {
        return nodes.findById(nodeId).map(InferenceNode::getOrganizationId).orElse(null);
    }

    private UUID endpointOrganization(UUID endpointId) {
        return endpoints.findById(endpointId).map(endpoint -> nodeOrganization(endpoint.getNodeId())).orElse(null);
    }

    private UUID deploymentOrganization(UUID deploymentId) {
        return deployments.findById(deploymentId).map(deployment -> endpointOrganization(deployment.getRuntimeEndpointId())).orElse(null);
    }

    private UUID uuid(JsonNode body, String field) {
        return body != null && body.hasNonNull(field) ? UUID.fromString(body.get(field).asText()) : null;
    }

    private JsonNode parse(byte[] body) {
        try {
            return body.length == 0 ? null : objectMapper.readTree(body);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void deny(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
