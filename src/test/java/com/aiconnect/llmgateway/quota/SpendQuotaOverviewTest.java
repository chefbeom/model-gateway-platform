package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.team.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpendQuotaOverviewTest {
    @Mock OrganizationRepository organizations;
    @Mock SpendQuotaRepository quotas;
    @Mock ProjectRepository projects;
    @Mock TeamRepository teams;
    @Mock ApiKeyRepository apiKeys;
    @Mock LlmRequestRepository requests;
    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final LocalDate end = LocalDate.of(2026, 10, 5);
    private SpendQuotaAdminController controller;

    @BeforeEach
    void setUp() {
        when(organizations.existsById(organizationId)).thenReturn(true);
        controller = new SpendQuotaAdminController(organizations, quotas, projects, teams, apiKeys, requests);
    }

    private void registerProject() {
        Project project = new Project(organizationId, "Usage project");
        ReflectionTestUtils.setField(project, "id", projectId);
        when(projects.findByOrganizationId(organizationId)).thenReturn(List.of(project));
    }

    @Test
    void allPeriodIncludesHistoryOlderThanThirtyDaysWithinTheOrganization() {
        registerProject();
        LlmRequest first = mock(LlmRequest.class);
        when(first.getStartedAt()).thenReturn(Instant.parse("2026-07-04T12:30:00Z"));
        when(requests.findFirstByProjectIdInAndStartedAtLessThanOrderByStartedAtAsc(
                List.of(projectId), Instant.parse("2026-10-06T00:00:00Z"))).thenReturn(Optional.of(first));

        var result = controller.overview(organizationId, null, end, true);

        assertThat(result.from()).isEqualTo(LocalDate.of(2026, 7, 4));
        assertThat(result.to()).isEqualTo(end);
        assertThat(result.series().get(0).date()).isEqualTo(result.from());
        assertThat(result.series().get(result.series().size() - 1).date()).isEqualTo(end);
        assertThat(result.series()).hasSize(94);
    }

    @Test
    void omittedAllFlagKeepsTheExistingThirtyDayDefault() {
        registerProject();

        var result = controller.overview(organizationId, null, end, false);

        assertThat(result.from()).isEqualTo(end.minusDays(29));
        assertThat(result.series()).hasSize(30);
        verify(requests, never()).findFirstByProjectIdInAndStartedAtLessThanOrderByStartedAtAsc(any(), any());
    }

    @Test
    void allPeriodForAnEmptyOrganizationDoesNotQueryOtherProjects() {
        when(projects.findByOrganizationId(organizationId)).thenReturn(List.of());

        var result = controller.overview(organizationId, null, end, true);

        assertThat(result.from()).isEqualTo(end);
        assertThat(result.series()).hasSize(1);
        verifyNoInteractions(requests);
    }
}
