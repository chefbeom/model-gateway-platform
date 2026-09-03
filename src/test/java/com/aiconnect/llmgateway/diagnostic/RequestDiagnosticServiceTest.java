package com.aiconnect.llmgateway.diagnostic;

import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.routing.RoutingDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RequestDiagnosticServiceTest {
    private final RequestDiagnosticRepository repository = mock(RequestDiagnosticRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RequestDiagnosticService service = new RequestDiagnosticService(repository, objectMapper);

    @Test
    void storedPayloadContainsSafeRequestSummaryAndRecommendationButNoPrompt() throws Exception {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("model", "text-pro");
        request.put("max_tokens", 4096);
        request.putArray("messages").addObject().put("role", "user").put("content", "customer secret prompt");
        request.putObject("response_format").put("type", "json_schema");
        UUID targetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        RoutingDecision decision = new RoutingDecision(List.of(), Set.of("STRUCTURED_OUTPUT"), false,
                FailoverPolicy.COMPATIBLE.name(), RetryPolicy.SAFE.name(), List.of(
                new RoutingDecision.TargetEvaluation(targetId, deploymentId, "Text model", "LOCAL", 1, 100,
                        true, false, true, true, "HEALTHY", "LM Studio", null, 0, 1,
                        List.of("STRUCTURED_OUTPUT"), List.of(), List.of("STRUCTURED_OUTPUT"), false,
                        List.of("CAPABILITY_MISSING"))));
        LlmService logicalService = new LlmService(UUID.randomUUID(), "text-pro", "Text Pro", FailoverPolicy.COMPATIBLE,
                RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO);

        service.recordFailure(UUID.randomUUID(), request, logicalService, decision,
                "MODEL_UNAVAILABLE", 503, 0);

        ArgumentCaptor<RequestDiagnostic> captor = ArgumentCaptor.forClass(RequestDiagnostic.class);
        verify(repository).save(captor.capture());
        String json = captor.getValue().getPayloadJson();
        RequestDiagnosticPayload payload = objectMapper.readValue(json, RequestDiagnosticPayload.class);

        assertThat(json).doesNotContain("customer secret prompt");
        assertThat(payload.request().hasMaxTokens()).isTrue();
        assertThat(payload.request().responseFormatType()).isEqualTo("json_schema");
        assertThat(payload.targets().get(0).missingCapabilities()).containsExactly("STRUCTURED_OUTPUT");
        assertThat(payload.recommendations()).extracting(RequestDiagnosticPayload.Recommendation::code)
                .contains("CAPABILITY_MISSING", "MODEL_UNAVAILABLE");
    }
}
