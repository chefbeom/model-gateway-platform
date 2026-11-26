package com.aiconnect.llmgateway.external;

import com.aiconnect.llmgateway.billing.TokenPricingResolver;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.ProjectExternalAccess;
import com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus;
import com.aiconnect.llmgateway.gateway.TokenUsageEstimator;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.ProjectExternalAccessRepository;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Atomically reserves and settles project monthly cost caps per external provider attempt. */
@Service
public class ProviderCapReservationService {
    private final ProjectExternalAccessRepository access;
    private final ProjectExternalAccessReservationRepository reservations;
    private final ModelDeploymentRepository deployments;
    private final LlmRequestRepository requests;
    private final long leaseSeconds;

    public ProviderCapReservationService(
            ProjectExternalAccessRepository access,
            ProjectExternalAccessReservationRepository reservations,
            ModelDeploymentRepository deployments,
            LlmRequestRepository requests,
            @Value("${aiconnect.quota.reservation-lease-seconds:1800}") long leaseSeconds) {
        this.access = access;
        this.reservations = reservations;
        this.deployments = deployments;
        this.requests = requests;
        this.leaseSeconds = leaseSeconds > 0 ? leaseSeconds : 1800;
    }

    @Transactional
    public ProviderReservation reserveForAttempt(UUID projectId, ExternalProvider provider, ModelDeployment deployment,
                                             LlmService service, ObjectNode proxiedRequest, String requestId) {
        if (projectId == null || provider == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EXTERNAL_ACCESS_NOT_ACTIVE",
                    "External provider access is not active for this project.");
        }
        ProjectExternalAccess item = access.findForUpdate(projectId, provider.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "EXTERNAL_ACCESS_NOT_ACTIVE",
                        "External provider access is not configured for this project."));
        if (!item.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EXTERNAL_ACCESS_NOT_ACTIVE",
                    "External provider access is not active for this project.");
        }
        if (item.getMonthlyCostLimit() == null) {
            return ProviderReservation.none();
        }
        Currency capCurrency = item.getMonthlyCostCurrency();
        if (capCurrency == null) {
            throw new ApiException(HttpStatus.CONFLICT, "EXTERNAL_PROVIDER_CAP_RESERVATION_UNAVAILABLE",
                    "The monthly provider cost limit has no configured currency.");
        }

        JsonNode completionNode = proxiedRequest == null ? null : proxiedRequest.get("max_completion_tokens");
        JsonNode legacyNode = proxiedRequest == null ? null : proxiedRequest.get("max_tokens");
        boolean hasCompletion = isPositiveInt(completionNode);
        boolean hasLegacy = isPositiveInt(legacyNode);
        if ((!hasCompletion && !hasLegacy)
                || (completionNode != null && !completionNode.isNull() && !hasCompletion)
                || (legacyNode != null && !legacyNode.isNull() && !hasLegacy)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "QUOTA_OUTPUT_LIMIT_REQUIRED",
                    "An explicit positive output token limit is required when a provider monthly cost limit is configured.");
        }
        int effectiveOutputLimit = Math.max(
                hasCompletion ? completionNode.asInt() : 0,
                hasLegacy ? legacyNode.asInt() : 0);
        JsonNode n = proxiedRequest.get("n");
        if (n != null && (!n.isIntegralNumber() || !n.canConvertToInt() || n.asInt() <= 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "QUOTA_CHOICE_COUNT_INVALID",
                    "The number of choices must be a positive integer when a provider monthly cost limit is configured.");
        }

        if (deployment.getProviderInputPricePerMillion() == null || deployment.getProviderOutputPricePerMillion() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "QUOTA_PRICE_UNAVAILABLE",
                    "Configure external model input and output prices before using a provider monthly cap.");
        }
        String requestedTier = proxiedRequest.path("service_tier").asText("");
        TokenPricingResolver.EffectivePricing pricing = TokenPricingResolver.forRequest(
                service, deployment, null, null, requestedTier);
        if (!pricing.rateConfigured() || pricing.inputPricePerMillion() == null || pricing.outputPricePerMillion() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "QUOTA_PRICE_UNAVAILABLE",
                    "Configure the selected external model tier prices before using a provider monthly cap.");
        }
        if (pricing.currency() != capCurrency) {
            throw new ApiException(HttpStatus.CONFLICT, "PROVIDER_CAP_CURRENCY_MISMATCH",
                    "The model pricing currency (" + pricing.currency()
                            + ") does not match the provider monthly cap currency (" + capCurrency + ").");
        }

        int inputTokens = TokenUsageEstimator.estimateInputTokens(proxiedRequest);
        long totalOutput = (long) effectiveOutputLimit * (n == null ? 1 : n.asInt());
        BigDecimal worstCase = pricing.inputPricePerMillion().multiply(BigDecimal.valueOf(inputTokens))
                .add(pricing.outputPricePerMillion().multiply(BigDecimal.valueOf(totalOutput)))
                .movePointLeft(6);
        BigDecimal reserveAmount = worstCase.signum() <= 0
                ? BigDecimal.ZERO.setScale(6, RoundingMode.CEILING)
                : worstCase.setScale(6, RoundingMode.CEILING);

        Instant now = Instant.now();
        ZonedDateTime utcNow = now.atZone(ZoneOffset.UTC);
        Instant monthStart = utcNow.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
        Instant monthEnd = utcNow.withDayOfMonth(1).plusMonths(1).truncatedTo(ChronoUnit.DAYS).toInstant();

        reservations.reclaimExpiredForAccess(projectId, provider.getId(), now);
        reservations.reclaimExpiredUnsettledForAccess(projectId, provider.getId(), now);

        List<UUID> deploymentIds = deployments.findByExternalProviderId(provider.getId())
                .stream().map(ModelDeployment::getId).toList();
        BigDecimal committed = deploymentIds.isEmpty()
                ? BigDecimal.ZERO
                : requests.sumEstimatedCostByProjectAndDeploymentsAndCurrencySince(
                        projectId, deploymentIds, capCurrency, monthStart);
        if (committed == null) committed = BigDecimal.ZERO;

        BigDecimal reserved = reservations.sumReservedAmount(projectId, provider.getId(), capCurrency, monthStart, now);
        if (reserved == null) reserved = BigDecimal.ZERO;

        BigDecimal used = committed.add(reserved);
        BigDecimal limit = item.getMonthlyCostLimit();
        if (used.compareTo(limit) >= 0 || used.add(reserveAmount).compareTo(limit) > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "EXTERNAL_PROVIDER_CAP_EXCEEDED",
                    "The project monthly cap for provider " + provider.getDisplayName() + " has been exceeded ("
                            + capCurrency + " " + used.stripTrailingZeros().toPlainString() + " / "
                            + limit.stripTrailingZeros().toPlainString() + ").");
        }

        UUID reservationKey = UUID.randomUUID();
        reservations.saveAndFlush(new ProjectExternalAccessReservation(
                projectId, provider.getId(), reservationKey, requestId,
                reserveAmount, capCurrency, monthStart, now.plusSeconds(leaseSeconds)));
        return new ProviderReservation(reservationKey, projectId, provider.getId(), monthEnd);
    }

    private static boolean isPositiveInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.asInt() > 0;
    }

    @Transactional
    public void settle(ProviderReservation reservation, String reason) {
        if (reservation == null || reservation.reservationKey() == null) return;
        reservations.transitionByKey(reservation.reservationKey(), ReservationStatus.ACTIVE,
                ReservationStatus.SETTLED, Instant.now(), truncateReason(reason));
    }

    @Transactional
    public void release(ProviderReservation reservation, String reason) {
        if (reservation == null || reservation.reservationKey() == null) return;
        reservations.transitionByKey(reservation.reservationKey(), ReservationStatus.ACTIVE,
                ReservationStatus.RELEASED, Instant.now(), truncateReason(reason));
    }

    @Transactional
    public void holdUnsettled(ProviderReservation reservation, String reason) {
        if (reservation == null || reservation.reservationKey() == null) return;
        Instant periodEnd = reservation.periodEnd() != null
                ? reservation.periodEnd()
                : Instant.now().atZone(ZoneOffset.UTC).withDayOfMonth(1).plusMonths(1).truncatedTo(ChronoUnit.DAYS).toInstant();
        reservations.holdUnsettledByKey(reservation.reservationKey(), periodEnd, truncateReason(reason));
    }

    @Transactional
    public int reclaimExpired() {
        return reservations.reclaimExpired(Instant.now());
    }

    @Transactional
    public int reclaimExpired(Instant now) {
        return reservations.reclaimExpired(now == null ? Instant.now() : now);
    }

    private static String truncateReason(String reason) {
        if (reason == null || reason.isBlank()) return null;
        return reason.length() <= 120 ? reason : reason.substring(0, 120);
    }

    public record ProviderReservation(UUID reservationKey, UUID projectId, UUID providerId, Instant periodEnd) {
        public static ProviderReservation none() {
            return new ProviderReservation(null, null, null, null);
        }

        public boolean isHeld() {
            return reservationKey != null;
        }
    }
}
