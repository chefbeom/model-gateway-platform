package com.aiconnect.llmgateway.cluster;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/deployment-profile")
public class DeploymentProfileController {
    private final DeploymentProfileProperties properties;
    private final Optional<RedisConnectionFactory> redis;

    public DeploymentProfileController(DeploymentProfileProperties properties,
                                       Optional<RedisConnectionFactory> redis) {
        this.properties = properties;
        this.redis = redis;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> profile() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", properties.getProfile());
        result.put("sharedStateProvider", properties.getSharedStateProvider());
        result.put("instanceId", properties.getInstanceId());
        result.put("redisConfigured", properties.getSharedStateProvider() == SharedStateProvider.REDIS && redis.isPresent());
        return ResponseEntity.ok(result);
    }
}
