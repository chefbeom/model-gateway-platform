package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpendQuotaPricingTest {
    @Test void payableCurrenciesMustBeCoveredAtEveryApplicableScopeAndPeriod() {
        UUID organization=UUID.randomUUID(), project=UUID.randomUUID();
        var orgKrw=new SpendQuota(organization,SpendQuotaScope.ORGANIZATION,organization,"org krw",Currency.KRW,BigDecimal.TEN,SpendQuotaPeriod.MONTHLY,true);
        var orgUsd=new SpendQuota(organization,SpendQuotaScope.ORGANIZATION,organization,"org usd",Currency.USD,BigDecimal.TEN,SpendQuotaPeriod.MONTHLY,true);
        var projectKrw=new SpendQuota(organization,SpendQuotaScope.PROJECT,project,"project krw",Currency.KRW,BigDecimal.TEN,SpendQuotaPeriod.MONTHLY,true);
        var projectUsd=new SpendQuota(organization,SpendQuotaScope.PROJECT,project,"project usd",Currency.USD,BigDecimal.TEN,SpendQuotaPeriod.MONTHLY,true);
        Map<Currency,BigDecimal> estimate=Map.of(Currency.KRW,BigDecimal.ZERO,Currency.USD,BigDecimal.ONE);
        assertThatThrownBy(() -> SpendQuotaService.requireCurrencyCoverage(List.of(orgKrw,projectUsd),estimate))
                .isInstanceOf(ApiException.class).hasMessageContaining("USD");
        assertThatThrownBy(() -> SpendQuotaService.requireCurrencyCoverage(List.of(orgKrw,orgUsd,projectKrw),estimate))
                .isInstanceOf(ApiException.class).hasMessageContaining("USD");
        assertThatCode(() -> SpendQuotaService.requireCurrencyCoverage(List.of(orgKrw,orgUsd,projectKrw,projectUsd),estimate)).doesNotThrowAnyException();
        assertThatCode(() -> SpendQuotaService.requireCurrencyCoverage(List.of(orgKrw),Map.of(Currency.USD,BigDecimal.ZERO))).doesNotThrowAnyException();
    }
    @Test void externalRuntimeRequiresExplicitPricesAndOutputLimitForBudgetReservation() throws Exception {
        var targets=mock(ServiceTargetRepository.class); var deployments=mock(ModelDeploymentRepository.class);
        var endpoints=mock(RuntimeEndpointRepository.class);
        var quota=new SpendQuotaService(mock(ApiKeyService.class),mock(SpendQuotaRepository.class),mock(SpendCostQuery.class),
                mock(ProjectRepository.class),mock(TeamRepository.class),mock(ApiKeyRepository.class),mock(LlmServiceRepository.class),
                mock(SpendQuotaReservationRepository.class),targets,deployments,endpoints);
        var trust=mock(com.aiconnect.llmgateway.security.RuntimeTrustPolicy.class);
        ReflectionTestUtils.setField(quota,"runtimeTrust",trust);
        UUID serviceId=UUID.randomUUID(),endpointId=UUID.randomUUID(),modelId=UUID.randomUUID();
        var service=new LlmService(UUID.randomUUID(),"service","Service",FailoverPolicy.COMPATIBLE,RetryPolicy.SAFE,false,"[]",BigDecimal.ZERO,BigDecimal.ZERO);
        ReflectionTestUtils.setField(service,"id",serviceId);
        var endpoint=new RuntimeEndpoint(UUID.randomUUID(),RuntimeType.LLAMA_CPP,"http://100.106.74.36:4040",null);
        ReflectionTestUtils.setField(endpoint,"id",endpointId);
        var model=new ModelDeployment(endpointId,"model","model","Model",null,null,8192,true,1,"[]");
        ReflectionTestUtils.setField(model,"id",modelId); ReflectionTestUtils.setField(model,"healthStatus",HealthStatus.HEALTHY);
        when(targets.findByServiceIdAndEnabledTrueOrderByPriorityAsc(serviceId)).thenReturn(List.of(new ServiceTarget(serviceId,modelId,1,100,false,null)));
        when(deployments.findAllById(any())).thenReturn(List.of(model));
        when(endpoints.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(trust.isVerifiedExternal(eq(endpoint),any())).thenReturn(true);
        var body=new ObjectMapper().readTree("{\"messages\":[],\"max_tokens\":100}");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(quota,"estimateRequestCosts",body,service))
                .isInstanceOf(ApiException.class).hasMessageContaining("prices");
        endpoint.configurePricing(BigDecimal.ONE,BigDecimal.TEN,Currency.USD);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(quota,"estimateRequestCosts",new ObjectMapper().createObjectNode(),service))
                .isInstanceOf(ApiException.class).hasMessageContaining("output token limit");
        Map<Currency,BigDecimal> result=ReflectionTestUtils.invokeMethod(quota,"estimateRequestCosts",body,service);
        assertThat(result.get(Currency.USD)).isEqualByComparingTo("0.001001");
    }
    @Test void explicitFastPriceAndChoiceCountAreReserved() throws Exception {
        var targets = mock(ServiceTargetRepository.class);
        var deployments = mock(ModelDeploymentRepository.class);
        var quota = new SpendQuotaService(mock(ApiKeyService.class), mock(SpendQuotaRepository.class), mock(SpendCostQuery.class),
                mock(ProjectRepository.class), mock(TeamRepository.class), mock(ApiKeyRepository.class), mock(LlmServiceRepository.class),
                mock(SpendQuotaReservationRepository.class), targets, deployments, mock(RuntimeEndpointRepository.class));
        UUID serviceId=UUID.randomUUID(), modelId=UUID.randomUUID();
        var service = new LlmService(UUID.randomUUID(), "service", "Service", FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO);
        ReflectionTestUtils.setField(service, "id", serviceId);
        var model=ModelDeployment.external(UUID.randomUUID(),"model","model","Model",8192,20,"[]",BigDecimal.ONE,BigDecimal.ONE, Currency.USD);
        ReflectionTestUtils.setField(model,"id",modelId);
        model.configureOpenAiDefaults(null, OpenAiServiceTier.FAST, null, BigDecimal.TEN, null, BigDecimal.TEN, false);
        var target=new ServiceTarget(serviceId, modelId, 1, 100, false, null);
        when(targets.findByServiceIdAndEnabledTrueOrderByPriorityAsc(serviceId)).thenReturn(List.of(target));
        when(deployments.findAllById(any())).thenReturn(List.of(model));
        var body=new ObjectMapper().readTree("{\"messages\":[],\"max_tokens\":100,\"n\":2}");
        Map<Currency,BigDecimal> result=ReflectionTestUtils.invokeMethod(quota,"estimateRequestCosts",body,service);
        // One estimated input token and 100 output tokens for each of two choices at the Fast rate.
        assertThat(result.get(Currency.USD)).isEqualByComparingTo("0.002010");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(quota,"estimateRequestCosts",new ObjectMapper().createObjectNode(),service))
                .isInstanceOf(ApiException.class).hasMessageContaining("output token limit");
    }

    @Test void fourStateReservationLifecycleTransitionsCorrectly() {
        var quotas = mock(SpendQuotaRepository.class);
        var reservations = mock(SpendQuotaReservationRepository.class);
        var service = new SpendQuotaService(mock(ApiKeyService.class), quotas, mock(SpendCostQuery.class),
                mock(ProjectRepository.class), mock(TeamRepository.class), mock(ApiKeyRepository.class),
                mock(LlmServiceRepository.class), reservations, mock(ServiceTargetRepository.class),
                mock(ModelDeploymentRepository.class), mock(RuntimeEndpointRepository.class));

        UUID key = UUID.randomUUID();
        UUID quotaId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        var reservation = new SpendQuotaService.Reservation(key);

        service.releaseIfStillActive(reservation);
        verify(reservations).transitionByKey(eq(key),
                eq(SpendQuotaReservation.ReservationStatus.ACTIVE),
                eq(SpendQuotaReservation.ReservationStatus.RELEASED),
                any(Instant.class), eq("RELEASED"));

        service.settle(reservation, "COMMITTED");
        verify(reservations).settleByKey(eq(key), any(Instant.class), eq("COMMITTED"));

        var row = new SpendQuotaReservation(quotaId, key, BigDecimal.TEN, Currency.KRW, Instant.now().plusSeconds(600));
        ReflectionTestUtils.setField(row, "id", rowId);
        var quota = new SpendQuota(UUID.randomUUID(), SpendQuotaScope.PROJECT, UUID.randomUUID(),
                "daily", Currency.KRW, BigDecimal.valueOf(100), SpendQuotaPeriod.DAILY, true);
        when(reservations.findByReservationKey(key)).thenReturn(List.of(row));
        when(quotas.findById(quotaId)).thenReturn(Optional.of(quota));

        service.holdUnsettled(reservation, "STREAM_INTERRUPTED_UNSETTLED");
        verify(reservations).holdUnsettledById(eq(rowId),
                argThat(exp -> exp.isAfter(Instant.now())),
                eq("STREAM_INTERRUPTED_UNSETTLED"));

        service.reclaimExpired();
        verify(reservations).reclaimExpiredActive(any(Instant.class));
    }
}
