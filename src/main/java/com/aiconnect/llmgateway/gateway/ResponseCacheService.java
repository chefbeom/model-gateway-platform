package com.aiconnect.llmgateway.gateway;

import com.aiconnect.llmgateway.dataprotection.DataProtectionAction;
import com.aiconnect.llmgateway.domain.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded exact-match response cache keyed by organization, service, prompt version,
 * active guardrail action, and canonicalized request payload.
 */
@Service
public class ResponseCacheService {
    private static final int MAX_ENTRIES = 1_000;
    private final ObjectMapper canonicalMapper;
    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();

    public ResponseCacheService(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String cacheKey(UUID organizationId, LlmService service, DataProtectionAction action, ObjectNode request) {
        try {
            String canonical = canonicalMapper.writeValueAsString(request);
            String raw = (organizationId == null ? "" : organizationId.toString())
                    + "|" + service.getId()
                    + "|v" + service.getPromptVersion()
                    + "|" + (action == null ? DataProtectionAction.ALLOW.name() : action.name())
                    + "|" + canonical;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            return null;
        }
    }

    public Optional<ObjectNode> get(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        CacheEntry entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (Instant.now().isAfter(entry.expiresAt())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.response().deepCopy());
    }

    public void put(String key, ObjectNode response, int ttlSeconds) {
        if (key == null || key.isBlank() || response == null) return;
        Instant now = Instant.now();
        if (entries.size() >= MAX_ENTRIES) {
            entries.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
            while (entries.size() >= MAX_ENTRIES) {
                var oldest = entries.entrySet().stream()
                        .min(java.util.Comparator.comparing(e -> e.getValue().createdAt()))
                        .orElse(null);
                if (oldest == null) break;
                entries.remove(oldest.getKey(), oldest.getValue());
            }
        }
        long clampedTtl = Math.max(10L, Math.min(86_400L, ttlSeconds));
        entries.put(key, new CacheEntry(response.deepCopy(), now, now.plusSeconds(clampedTtl)));
    }

    public void clear() {
        entries.clear();
    }

    private record CacheEntry(ObjectNode response, Instant createdAt, Instant expiresAt) { }
}
