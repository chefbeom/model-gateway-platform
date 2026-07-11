package com.aiconnect.llmgateway.alert;

import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.RequestStatus;
import com.aiconnect.llmgateway.notification.NotificationService;
import com.aiconnect.llmgateway.quota.ProjectQuota;
import com.aiconnect.llmgateway.quota.ProjectQuotaRepository;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

@Component
public class UsageAlertMonitor {
    private final ProjectAlertPolicyRepository policies;
    private final UsageAlertStateRepository states;
    private final ProjectRepository projects;
    private final ProjectQuotaRepository quotas;
    private final LlmRequestRepository requests;
    private final NotificationService notifications;

    public UsageAlertMonitor(ProjectAlertPolicyRepository policies, UsageAlertStateRepository states, ProjectRepository projects,
                             ProjectQuotaRepository quotas, LlmRequestRepository requests, NotificationService notifications) {
        this.policies = policies;
        this.states = states;
        this.projects = projects;
        this.quotas = quotas;
        this.requests = requests;
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${gateway.usage-alert-check-delay-ms:60000}")
    @Transactional
    public void checkPolicies() {
        Instant now = Instant.now();
        for (ProjectAlertPolicy policy : policies.findAll()) {
            Project project = projects.findById(policy.getProjectId()).orElse(null);
            if (project == null) continue;
            evaluateMinuteWindow(project, policy, now);
            evaluateMonthlyTokenUsage(project, policy, now);
        }
    }

    private void evaluateMinuteWindow(Project project, ProjectAlertPolicy policy, Instant now) {
        List<LlmRequest> window = requests.findByProjectIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
                project.getId(), now.minusSeconds(60), now.plusMillis(1));
        if (policy.getRequestsPerMinuteThreshold() != null) {
            notifyIfNeeded(project, policy, UsageAlertMetric.REQUESTS_PER_MINUTE,
                    BigDecimal.valueOf(window.size()), BigDecimal.valueOf(policy.getRequestsPerMinuteThreshold()), now);
        }
        if (policy.getErrorRatePercentThreshold() != null && !window.isEmpty()) {
            long failed = window.stream().filter(request -> request.getStatus() == RequestStatus.FAILED).count();
            BigDecimal errorRate = BigDecimal.valueOf(failed).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(window.size()), 2, RoundingMode.HALF_UP);
            notifyIfNeeded(project, policy, UsageAlertMetric.ERROR_RATE_PERCENT, errorRate,
                    policy.getErrorRatePercentThreshold(), now);
        }
    }

    private void evaluateMonthlyTokenUsage(Project project, ProjectAlertPolicy policy, Instant now) {
        if (policy.getMonthlyTokenUsagePercentThreshold() == null) return;
        ProjectQuota quota = quotas.findById(project.getId()).orElse(null);
        if (quota == null || quota.getMonthlyTokenLimit() == null || quota.getMonthlyTokenLimit() <= 0) return;
        Instant monthStart = ZonedDateTime.ofInstant(now, ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        long used = requests.sumTokensByProjectSince(project.getId(), monthStart);
        BigDecimal percent = BigDecimal.valueOf(used).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(quota.getMonthlyTokenLimit()), 2, RoundingMode.HALF_UP);
        notifyIfNeeded(project, policy, UsageAlertMetric.MONTHLY_TOKEN_USAGE_PERCENT, percent,
                BigDecimal.valueOf(policy.getMonthlyTokenUsagePercentThreshold()), now);
    }

    private void notifyIfNeeded(Project project, ProjectAlertPolicy policy, UsageAlertMetric metric,
                                BigDecimal observed, BigDecimal threshold, Instant now) {
        if (observed.compareTo(threshold) < 0) return;
        UsageAlertStateId id = new UsageAlertStateId(project.getId(), metric);
        UsageAlertState state = states.findById(id).orElseGet(() -> new UsageAlertState(project.getId(), metric));
        if (state.getLastSentAt() != null && state.getLastSentAt().plusSeconds(policy.getCooldownSeconds()).isAfter(now)) return;
        notifications.usageThresholdExceeded(project, metric, observed, threshold);
        state.markSent(now);
        states.save(state);
    }
}
