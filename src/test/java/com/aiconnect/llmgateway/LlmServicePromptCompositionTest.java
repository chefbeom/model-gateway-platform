package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LlmServicePromptCompositionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmService service(String prompt) {
        LlmService s = new LlmService(UUID.randomUUID(), "k", "K", FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]",
                BigDecimal.ZERO, BigDecimal.ZERO);
        if (prompt != null) s.configureSystemPromptTemplate(prompt, false);
        return s;
    }

    private ObjectNode request(String systemContent) throws Exception {
        String sys = systemContent == null ? "" : "{\"role\":\"system\",\"content\":\"" + systemContent + "\"},";
        return (ObjectNode) MAPPER.readTree("{\"messages\":[" + sys + "{\"role\":\"user\",\"content\":\"hi\"}]}");
    }

    @Test
    void nullOrBlankTargetPromptMatchesLegacyBehaviour() throws Exception {
        for (String target : new String[]{null, "", "   "}) {
            ObjectNode legacy = request("C"), viaNew = request("C");
            service("S").applySystemPromptTemplate(legacy);
            service("S").applySystemPromptTemplate(viaNew, target);
            assertThat(viaNew).isEqualTo(legacy);
            assertThat(viaNew.path("messages").get(0).path("content").asText()).isEqualTo("S\n\nC");
        }
        ObjectNode none = request("C");
        service(null).applySystemPromptTemplate(none, null);
        assertThat(none).isEqualTo(request("C"));
    }

    @Test
    void composesServiceThenTargetThenClientPrompt() throws Exception {
        ObjectNode req = request("C");
        service("S").applySystemPromptTemplate(req, "  T  ");
        assertThat(req.path("messages")).hasSize(2);
        assertThat(req.path("messages").get(0).path("content").asText()).isEqualTo("S\n\nT\n\nC");
    }

    @Test
    void targetOnlyAndInsertionWhenNoSystemMessage() throws Exception {
        ObjectNode withClient = request("C");
        service(null).applySystemPromptTemplate(withClient, "T");
        assertThat(withClient.path("messages").get(0).path("content").asText()).isEqualTo("T\n\nC");

        ObjectNode noSystem = request(null);
        service("S").applySystemPromptTemplate(noSystem, "T");
        assertThat(noSystem.path("messages")).hasSize(2);
        assertThat(noSystem.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(noSystem.path("messages").get(0).path("content").asText()).isEqualTo("S\n\nT");
        assertThat(noSystem.path("messages").get(1).path("role").asText()).isEqualTo("user");
    }

    @Test
    void bumpPromptVersionIncrements() {
        LlmService s = service(null);
        int before = s.getPromptVersion();
        s.bumpPromptVersion();
        assertThat(s.getPromptVersion()).isEqualTo(before + 1);
    }
}
