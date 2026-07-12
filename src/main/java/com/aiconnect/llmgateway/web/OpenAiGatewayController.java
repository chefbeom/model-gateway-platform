package com.aiconnect.llmgateway.web;

import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ProjectServiceAccess;
import com.aiconnect.llmgateway.gateway.ChatCompletionGateway;
import com.aiconnect.llmgateway.gateway.GatewayResult;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ProjectServiceAccessRepository;
import com.aiconnect.llmgateway.service.ApiKeyCredentials;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class OpenAiGatewayController {
    private final ChatCompletionGateway gateway;
    private final ApiKeyService apiKeyService;
    private final LlmServiceRepository services;
    private final ProjectServiceAccessRepository access;
    private final ObjectMapper objectMapper;

    public OpenAiGatewayController(ChatCompletionGateway gateway, ApiKeyService apiKeyService,
                                   LlmServiceRepository services, ProjectServiceAccessRepository access,
                                   ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.apiKeyService = apiKeyService;
        this.services = services;
        this.access = access;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<JsonNode> chatCompletions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody JsonNode body) {
        GatewayResult result = gateway.complete(authorization, body);
        return ResponseEntity.status(result.statusCode()).header("X-Request-Id", result.requestId()).body(result.body());
    }

    @GetMapping("/models")
    public ObjectNode models(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        ApiKeyCredentials credentials = apiKeyService.authenticate(authorization);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("object", "list");
        ArrayNode models = response.putArray("data");
        for (ProjectServiceAccess entitlement : access.findByIdProjectId(credentials.project().getId())) {
            services.findById(entitlement.getId().getServiceId()).filter(LlmService::isEnabled).ifPresent(service -> {
                ObjectNode model = models.addObject();
                model.put("id", service.getServiceKey());
                model.put("object", "model");
                model.put("owned_by", "aiconnect");
            });
        }
        return response;
    }
}
