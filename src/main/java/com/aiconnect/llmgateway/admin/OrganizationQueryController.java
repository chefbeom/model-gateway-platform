package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.identity.OrganizationMemberRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.team.TeamAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class OrganizationQueryController {
    private final OrganizationRepository organizations;
    private final ProjectRepository projects;
    private final OrganizationMemberRepository memberships;
    private final TeamAccessService access;

    public OrganizationQueryController(OrganizationRepository organizations, ProjectRepository projects,
                                       OrganizationMemberRepository memberships, TeamAccessService access) {
        this.organizations = organizations;
        this.projects = projects;
        this.memberships = memberships;
        this.access = access;
    }

    @GetMapping("/organizations")
    public List<AdminController.OrganizationView> organizations(HttpServletRequest request) {
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (Boolean.TRUE.equals(request.getAttribute("aiconnect.platform-admin")) || (actor != null && actor.platformAdmin())) {
            return organizations.findAll().stream().map(AdminController.OrganizationView::from).toList();
        }
        if (actor == null) return List.of();
        return memberships.findByIdUserId(actor.userId()).stream()
                .map(member -> member.getId().getOrganizationId())
                .distinct()
                .map(organizations::findById)
                .flatMap(java.util.Optional::stream)
                .map(AdminController.OrganizationView::from)
                .toList();
    }

    @GetMapping("/organizations/{organizationId}/projects")
    public List<AdminController.ProjectView> projects(@PathVariable UUID organizationId) {
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        return projects.findByOrganizationId(organizationId).stream()
                .filter(project -> access.canViewProject(actor, project.getId()))
                .map(AdminController.ProjectView::from)
                .toList();
    }
}
