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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

    @PostMapping("/targets/{targetId}/probe")
    public ModelPlaygroundService.ProbeView probe(@PathVariable UUID organizationId, @PathVariable UUID targetId) {
        return playground.probe(organizationId, targetId);
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> chat(@PathVariable UUID organizationId, @RequestBody ObjectNode body) {
        ModelPlaygroundService.ChatResult result = playground.chat(organizationId, body);
        if (result.streaming() != null) {
            return ResponseEntity.status(result.statusCode())
                    .header("X-Request-Id", result.requestId())
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header("X-Accel-Buffering", "no")
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(result.streaming());
        }
        return ResponseEntity.status(result.statusCode())
                .header("X-Request-Id", result.requestId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
