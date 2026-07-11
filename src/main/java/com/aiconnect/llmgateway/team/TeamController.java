package com.aiconnect.llmgateway.team;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/organizations/{organizationId}/teams")
public class TeamController {
    private final TeamService service;

    public TeamController(TeamService service) {
        this.service = service;
    }

    @GetMapping
    public List<TeamView> list(@PathVariable UUID organizationId) {
        return service.list(organizationId).stream().map(TeamView::from).toList();
    }

    @PostMapping
    public TeamView create(@PathVariable UUID organizationId, @Valid @RequestBody CreateTeam request) {
        return TeamView.from(service.create(organizationId, request.name()));
    }

    @GetMapping("/{teamId}/members")
    public List<TeamMemberView> members(@PathVariable UUID organizationId, @PathVariable UUID teamId) {
        return service.members(organizationId, teamId).stream().map(TeamMemberView::from).toList();
    }

    @PostMapping("/{teamId}/members")
    public TeamMemberView grantMembership(@PathVariable UUID organizationId, @PathVariable UUID teamId,
                                          @Valid @RequestBody GrantTeamMembership request) {
        return TeamMemberView.from(service.grantMembership(organizationId, teamId, request.userId(), request.role()));
    }

    public record CreateTeam(@NotBlank @Size(max = 120) String name) { }
    public record GrantTeamMembership(@NotNull UUID userId, @NotNull TeamRole role) { }
    public record TeamView(UUID id, UUID organizationId, String name, String status) {
        static TeamView from(Team team) { return new TeamView(team.getId(), team.getOrganizationId(), team.getName(), team.getStatus()); }
    }
    public record TeamMemberView(UUID teamId, UUID userId, TeamRole role) {
        static TeamMemberView from(TeamMember member) {
            return new TeamMemberView(member.getId().getTeamId(), member.getId().getUserId(), member.getRole());
        }
    }
}
