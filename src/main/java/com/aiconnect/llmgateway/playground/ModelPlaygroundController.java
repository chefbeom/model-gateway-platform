package com.aiconnect.llmgateway.playground;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/organizations/{organizationId}/playground")
public class ModelPlaygroundController {
    private final ModelPlaygroundService playground;

    public ModelPlaygroundController(ModelPlaygroundService playground) { this.playground = playground; }

    @GetMapping("/targets")
    public List<ModelPlaygroundService.TargetView> targets(@PathVariable UUID organizationId) {
        return playground.targets(organizationId);
    }

    @GetMapping("/requests")
    public List<ModelPlaygroundService.TraceView> requests(@PathVariable UUID organizationId) {
        return playground.requests(organizationId);
    }

    @GetMapping("/conversations")
    public List<PlaygroundConversationService.ConversationSummary> conversations(
            @PathVariable UUID organizationId, @RequestParam(required = false) String query) {
        return playground.conversations(organizationId, query);
    }

    @GetMapping("/conversations/{conversationId}")
    public PlaygroundConversationService.ConversationDetail conversation(
            @PathVariable UUID organizationId, @PathVariable UUID conversationId) {
        return playground.conversation(organizationId, conversationId);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable UUID organizationId,
                                                    @PathVariable UUID conversationId) {
        playground.deleteConversation(organizationId, conversationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/targets/{targetId}/probe")
    public ModelPlaygroundService.ProbeView probe(@PathVariable UUID organizationId, @PathVariable UUID targetId) {
        return playground.probe(organizationId, targetId);
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(@PathVariable UUID organizationId, @RequestBody ObjectNode body) {
        ModelPlaygroundService.ChatResult result = playground.chat(organizationId, body);
        boolean streaming = result.streaming() != null;
        StreamingResponseBody responseBody = streaming ? result.streaming()
                : output -> output.write(result.body().toString().getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.status(result.statusCode())
                .header("X-Request-Id", result.requestId())
                .headers(headers -> {
                    if (result.conversationId() != null) headers.set("X-Playground-Conversation-Id", result.conversationId().toString());
                })
                .headers(headers -> {
                    if (streaming) {
                        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache");
                        headers.set("X-Accel-Buffering", "no");
                    }
                })
                .contentType(streaming ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON)
                .body(responseBody);
    }
}
