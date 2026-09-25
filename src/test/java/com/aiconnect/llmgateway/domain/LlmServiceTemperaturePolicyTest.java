package com.aiconnect.llmgateway.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmServiceTemperaturePolicyTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void requestPolicyPreservesCallerValueByDefault() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("{\"temperature\":0.2}");

        service().applyTemperaturePolicy(request);

        assertThat(request.path("temperature").asDouble()).isEqualTo(0.2);
    }

    @Test
    void fixedPolicyReplacesCallerValue() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("{\"temperature\":0.2}");
        LlmService service = service();
        service.configureTemperaturePolicy(TemperaturePolicy.FIXED, BigDecimal.ONE);

        service.applyTemperaturePolicy(request);

        assertThat(request.path("temperature").asDouble()).isEqualTo(1.0);
    }

    @Test
    void omitPolicyRemovesCallerValueAndUsesTargetDefault() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("{\"temperature\":0.2}");
        LlmService service = service();
        service.configureTemperaturePolicy(TemperaturePolicy.OMIT, null);

        service.applyTemperaturePolicy(request);

        assertThat(request.has("temperature")).isFalse();
    }

    @Test
    void fixedPolicyRequiresAValue() {
        assertThatThrownBy(() -> service().configureTemperaturePolicy(TemperaturePolicy.FIXED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openAiRequestDefaultsPreserveIncomingReasoningAndServiceTier() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("""
                {"reasoning_effort":"low","service_tier":"default"}
                """);

        service().applyOpenAiRequestPolicy(request);

        assertThat(request.path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(request.path("service_tier").asText()).isEqualTo("default");
    }

    @Test
    void openAiRequestPolicyOverridesReasoningAndEnablesFastMode() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("""
                {"reasoning_effort":"low","service_tier":"default"}
                """);
        LlmService service = service();
        service.configureOpenAiRequestPolicy(ReasoningEffort.HIGH, true);

        service.applyOpenAiRequestPolicy(request);

        assertThat(request.path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(request.path("service_tier").asText()).isEqualTo("fast");
    }

    private LlmService service() {
        return new LlmService(UUID.randomUUID(), "temperature-test", "Temperature Test",
                FailoverPolicy.STRICT, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
