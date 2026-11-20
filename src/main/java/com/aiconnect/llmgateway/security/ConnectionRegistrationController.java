package com.aiconnect.llmgateway.security;

import com.aiconnect.llmgateway.admin.AdminDtos;
import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.external.ExternalProviderAdministrationService;
import com.aiconnect.llmgateway.identity.*;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class ConnectionRegistrationController {
    private final ConnectionRegistrationRequestRepository requests;
    private final ProjectRepository projects;
    private final ProjectMembershipRepository memberships;
    private final AppUserRepository users;
    private final TeamAccessService access;
    private final SecretCipher cipher;
    private final ControlPlaneService control;
    private final ExternalProviderAdministrationService providers;
    private final AuditService audit;
    public ConnectionRegistrationController(ConnectionRegistrationRequestRepository requests, ProjectRepository projects,
            ProjectMembershipRepository memberships, AppUserRepository users, TeamAccessService access,
            SecretCipher cipher, ControlPlaneService control, ExternalProviderAdministrationService providers, AuditService audit) {
        this.requests=requests; this.projects=projects; this.memberships=memberships; this.users=users;
        this.access=access; this.cipher=cipher; this.control=control; this.providers=providers; this.audit=audit;
    }

    @PostMapping("/api/portal/projects/{projectId}/connection-requests")
    @Transactional
    public View submit(@PathVariable UUID projectId, @Valid @RequestBody Submit body) {
        AuthPrincipal actor=CurrentActor.principal().orElseThrow(() -> error(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED"));
        if (actor.accountTier()!=AccountTier.PROJECT_DIRECTOR || !access.canManageProject(actor,projectId))
            throw error(HttpStatus.FORBIDDEN,"PROJECT_MANAGER_REQUIRED");
        var project=projects.findById(projectId).orElseThrow(() -> error(HttpStatus.NOT_FOUND,"PROJECT_NOT_FOUND"));
        if (!"ACTIVE".equals(project.getStatus())) throw error(HttpStatus.CONFLICT,"PROJECT_NOT_ACTIVE");
        if (body.kind()==ConnectionRegistrationRequest.Kind.RUNTIME && body.runtimeType()==null)
            throw error(HttpStatus.BAD_REQUEST,"RUNTIME_TYPE_REQUIRED");
        if (body.kind()==ConnectionRegistrationRequest.Kind.EXTERNAL_PROVIDER && (body.secret()==null || body.secret().isBlank()))
            throw error(HttpStatus.BAD_REQUEST,"API_KEY_REQUIRED");
        String url=validateUrl(body.baseUrl());
        var request=requests.saveAndFlush(new ConnectionRegistrationRequest(projectId,actor.userId(),body.kind(),
                body.displayName().trim(),url,body.kind()==ConnectionRegistrationRequest.Kind.RUNTIME ? body.runtimeType():null,
                cipher.encrypt(body.secret()),body.reason().trim()));
        audit.record(project.getOrganizationId(),actor.userId(),"CONNECTION_REGISTRATION_REQUESTED","PROJECT",projectId,
                Map.of("requestId",request.getId().toString(),"kind",body.kind().name()));
        return View.from(request);
    }
    @GetMapping("/api/portal/projects/{projectId}/connection-requests")
    @Transactional(readOnly=true)
    public List<View> listProject(@PathVariable UUID projectId) {
        AuthPrincipal actor=CurrentActor.principal().orElseThrow(() -> error(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED"));
        if (!access.canManageProject(actor,projectId)) throw error(HttpStatus.FORBIDDEN,"PROJECT_MANAGER_REQUIRED");
        return requests.findByProjectIdOrderByCreatedAtDesc(projectId).stream().map(View::from).toList();
    }
    @GetMapping("/api/admin/platform/connection-requests")
    @Transactional(readOnly=true)
    public List<View> pending(HttpServletRequest http) {
        requirePlatform(http);
        return requests.findByStatusOrderByCreatedAtDesc(ConnectionRegistrationRequest.Status.PENDING).stream().map(View::from).toList();
    }
    @PostMapping("/api/admin/platform/connection-requests/{id}/decision")
    @Transactional
    public View decide(@PathVariable UUID id, @Valid @RequestBody Decision body, HttpServletRequest http) {
        requirePlatform(http);
        var request=requests.findForUpdate(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND,"CONNECTION_REQUEST_NOT_FOUND"));
        if (request.getStatus()!=ConnectionRegistrationRequest.Status.PENDING) throw error(HttpStatus.CONFLICT,"CONNECTION_REQUEST_ALREADY_DECIDED");
        var project=projects.findById(request.getProjectId()).orElseThrow(() -> error(HttpStatus.NOT_FOUND,"PROJECT_NOT_FOUND"));
        UUID resultId=null;
        if (body.approved()) {
            var user=users.findById(request.getRequesterId()).orElseThrow(() -> error(HttpStatus.CONFLICT,"REQUESTER_UNAVAILABLE"));
            boolean stillManager=user.isEnabled() && AccountTier.effectiveFor(user)==AccountTier.PROJECT_DIRECTOR
                    && memberships.findById(new ProjectMembershipId(project.getId(),user.getId()))
                        .map(ProjectMembership::getRole).filter(r -> r==ProjectMembership.Role.MANAGER).isPresent();
            if (!stillManager || !"ACTIVE".equals(project.getStatus())) throw error(HttpStatus.CONFLICT,"REQUESTER_SCOPE_CHANGED");
            String secret=cipher.decrypt(request.getEncryptedSecret());
            if (request.getKind()==ConnectionRegistrationRequest.Kind.RUNTIME) {
                var node=control.create(new AdminDtos.CreateNode(project.getOrganizationId(),request.getDisplayName(),null,"DIRECT",null));
                var endpoint=control.create(new AdminDtos.CreateEndpoint(node.getId(),request.getDisplayName(),request.getRuntimeType(),request.getBaseUrl(),secret,null,null,null));
                resultId=endpoint.getId();
            } else {
                resultId=providers.create(project.getOrganizationId(),request.getDisplayName(),request.getBaseUrl(),secret).id();
            }
        }
        request.decide(body.approved()?ConnectionRegistrationRequest.Status.APPROVED:ConnectionRegistrationRequest.Status.REJECTED,
                CurrentActor.userIdOrNull(),body.reason().trim(),resultId);
        audit.record(project.getOrganizationId(),CurrentActor.userIdOrNull(),"CONNECTION_REGISTRATION_DECIDED","PROJECT",project.getId(),
                Map.of("requestId",request.getId().toString(),"approved",body.approved(),"kind",request.getKind().name()));
        return View.from(request);
    }
    private String validateUrl(String value) {
        try {
            URI uri=URI.create(value.trim());
            if (!List.of("http","https").contains(uri.getScheme()==null?"":uri.getScheme().toLowerCase()) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException();
            return value.trim();
        } catch (IllegalArgumentException exception) { throw error(HttpStatus.BAD_REQUEST,"INVALID_CONNECTION_URL"); }
    }
    private void requirePlatform(HttpServletRequest request) {
        AuthPrincipal actor=CurrentActor.principal().orElse(null);
        if (!Boolean.TRUE.equals(request.getAttribute("aiconnect.platform-admin")) && (actor==null || !actor.platformAdmin()))
            throw error(HttpStatus.FORBIDDEN,"PLATFORM_ADMIN_REQUIRED");
    }
    private ApiException error(HttpStatus status,String code) { return new ApiException(status,code,code); }
    public record Submit(@NotNull ConnectionRegistrationRequest.Kind kind, @NotBlank @Size(max=160) String displayName,
                         @NotBlank @Size(max=500) String baseUrl, RuntimeType runtimeType, @Size(max=2000) String secret,
                         @NotBlank @Size(max=1000) String reason) { }
    public record Decision(boolean approved, @NotBlank @Size(max=1000) String reason) { }
    public record View(UUID id,UUID projectId,UUID requesterId,ConnectionRegistrationRequest.Kind kind,String displayName,
                       String baseUrl,RuntimeType runtimeType,String reason,ConnectionRegistrationRequest.Status status,
                       Instant createdAt,Instant decidedAt,UUID decidedBy,String decisionReason,UUID resultId) {
        static View from(ConnectionRegistrationRequest r) { return new View(r.getId(),r.getProjectId(),r.getRequesterId(),r.getKind(),r.getDisplayName(),r.getBaseUrl(),r.getRuntimeType(),r.getReason(),r.getStatus(),r.getCreatedAt(),r.getDecidedAt(),r.getDecidedBy(),r.getDecisionReason(),r.getResultId()); }
    }
}
