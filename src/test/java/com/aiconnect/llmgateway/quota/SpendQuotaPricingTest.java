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
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpendQuotaPricingTest {
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
}
