package com.aiconnect.llmgateway.external;

import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.ExternalAccessStatus;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.ExternalProviderType;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.ProjectExternalAccess;
import com.aiconnect.llmgateway.identity.AppUserRepository;
import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProjectExternalAccessCapTest {
    @Test
    void legacyNullCurrencyCapFailsClosedWhileExplicitCurrencyAllowsRouting() {
        UUID projectId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        var repository = mock(ProjectExternalAccessRepository.class);
        var service = new ProjectExternalAccessService(repository, mock(ExternalProviderRepository.class),
                mock(ProjectRepository.class), mock(ModelDeploymentRepository.class), mock(LlmRequestRepository.class),
                mock(AppUserRepository.class), mock(AuditService.class));

        var access = new ProjectExternalAccess(projectId, providerId, UUID.randomUUID(), "test");
        // Legacy row with monthlyCostLimit but null monthlyCostCurrency must fail closed.
        access.decide(ExternalAccessStatus.APPROVED, true, true, BigDecimal.TEN, null, null);
        when(repository.findByProjectIdAndProviderId(projectId, providerId)).thenReturn(Optional.of(access));
        assertThat(service.allowsManual(projectId, providerId)).isFalse();
        assertThat(service.allowsAutoFailover(projectId, providerId)).isFalse();
        assertThat(service.hasUnenforcedMonthlyLimit(projectId, providerId)).isTrue();

        // Explicit currency enables routing (atomic per-attempt enforcement runs in ProviderCapReservationService).
        access.decide(ExternalAccessStatus.APPROVED, true, true, BigDecimal.TEN, Currency.KRW, null, null);
        assertThat(service.allowsManual(projectId, providerId)).isTrue();
        assertThat(service.allowsAutoFailover(projectId, providerId)).isTrue();
        assertThat(service.hasUnenforcedMonthlyLimit(projectId, providerId)).isFalse();

        // Unlimited access remains allowed.
        access.decide(ExternalAccessStatus.APPROVED, true, true, null, null, null, null);
        assertThat(service.allowsManual(projectId, providerId)).isTrue();
        assertThat(service.allowsAutoFailover(projectId, providerId)).isTrue();
        assertThat(service.hasUnenforcedMonthlyLimit(projectId, providerId)).isFalse();
    }

    @Test
    void decideRequiresCurrencyWhenMonthlyCostLimitConfigured() {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        var repository = mock(ProjectExternalAccessRepository.class);
        var providers = mock(ExternalProviderRepository.class);
        var projects = mock(ProjectRepository.class);
        var service = new ProjectExternalAccessService(repository, providers,
                projects, mock(ModelDeploymentRepository.class), mock(LlmRequestRepository.class),
                mock(AppUserRepository.class), mock(AuditService.class));

        Project project = new Project(orgId, "cap-proj");
        ReflectionTestUtils.setField(project, "id", projectId);
        ExternalProvider provider = new ExternalProvider(orgId, ExternalProviderType.OPENAI, "OpenAI", "https://api.openai.com/v1", "enc");
        ReflectionTestUtils.setField(provider, "id", providerId);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(providers.findById(providerId)).thenReturn(Optional.of(provider));

        assertThatThrownBy(() -> service.decide(projectId, providerId, ExternalAccessStatus.APPROVED,
                true, false, BigDecimal.TEN, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getCode()).isEqualTo("PROVIDER_CAP_CURRENCY_REQUIRED");
                });
    }
}
