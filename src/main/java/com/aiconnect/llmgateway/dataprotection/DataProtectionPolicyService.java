package com.aiconnect.llmgateway.dataprotection;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.DataProtectionPolicyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resolves the most restrictive applicable policy for one request. */
@Service
public class DataProtectionPolicyService {
    private final DataProtectionPolicyRepository policies;
    private final ApiKeyRepository apiKeys;
    private final DataProtectionScanner scanner;
    private final ObjectMapper objectMapper;

    public DataProtectionPolicyService(DataProtectionPolicyRepository policies, ApiKeyRepository apiKeys,
                                       DataProtectionScanner scanner, ObjectMapper objectMapper) {
        this.policies = policies;
        this.apiKeys = apiKeys;
        this.scanner = scanner;
        this.objectMapper = objectMapper;
    }

    public DataProtectionDecision inspect(Project project, ApiKey apiKey, LlmService service,
                                         JsonNode request, String requestedProtection) {
        EffectiveDataProtectionPolicy effective = resolve(project, apiKey, service);
        effective = strengthen(effective, requestedProtection);
        DataProtectionScanResult scan = scanner.scan(request, effective);
        List<String> reasons = new ArrayList<>();
        for (String label : scan.labels()) reasons.add("DATA_DETECTED_" + label);

        DataProtectionAction action = DataProtectionAction.ALLOW;
        if (scan.hasSensitiveData()) {
            if (effective.mode() == DataProtectionMode.MONITOR) {
                reasons.add("DATA_PROTECTION_MONITOR_ONLY");
            } else if (effective.mode() == DataProtectionMode.ENFORCE) {
                action = effective.externalAction();
                if (action == DataProtectionAction.REDACT) {
                    // Redaction is intentionally fail-closed until a field-aware
                    // redactor is configured; raw sensitive text must not escape.
                    action = DataProtectionAction.LOCAL_ONLY;
                    reasons.add("DATA_PROTECTION_REDACTION_UNAVAILABLE");
                }
                reasons.add("DATA_PROTECTION_ENFORCED");
            }
        }
        boolean externalAllowed = action == DataProtectionAction.ALLOW;
        boolean externalFailoverAllowed = externalAllowed && effective.allowExternalFailover();
        if (!externalAllowed) reasons.add("DATA_PROTECTION_EXTERNAL_BLOCKED");
        else if (!externalFailoverAllowed) reasons.add("DATA_PROTECTION_EXTERNAL_FAILOVER_BLOCKED");
        return new DataProtectionDecision(effective, scan, action, externalAllowed,
                externalFailoverAllowed, List.copyOf(new LinkedHashSet<>(reasons)));
    }

    public EffectiveDataProtectionPolicy resolve(Project project, ApiKey apiKey, LlmService service) {
        UUID organizationId = project == null ? service == null ? null : service.getOrganizationId() : project.getOrganizationId();
        List<DataProtectionPolicy> applicable = new ArrayList<>();
        policies.findByScopeTypeAndOrganizationIdIsNullAndScopeIdIsNull(DataProtectionScopeType.PLATFORM)
                .ifPresent(applicable::add);
        if (organizationId != null) {
            find(DataProtectionScopeType.ORGANIZATION, organizationId, organizationId).ifPresent(applicable::add);
            if (project != null && project.getId() != null) find(DataProtectionScopeType.PROJECT, organizationId, project.getId()).ifPresent(applicable::add);
            if (service != null && service.getId() != null) find(DataProtectionScopeType.SERVICE, organizationId, service.getId()).ifPresent(applicable::add);
            if (apiKey != null && apiKey.getId() != null) find(DataProtectionScopeType.API_KEY, organizationId, apiKey.getId()).ifPresent(applicable::add);
        }
        return merge(applicable);
    }

    /** Resolves only the platform baseline and organization policy for the admin summary. */
    @Transactional(readOnly = true)
    public EffectiveDataProtectionPolicy resolveOrganization(UUID organizationId) {
        List<DataProtectionPolicy> applicable = new ArrayList<>();
        policies.findByScopeTypeAndOrganizationIdIsNullAndScopeIdIsNull(DataProtectionScopeType.PLATFORM)
                .ifPresent(applicable::add);
        if (organizationId != null) {
            find(DataProtectionScopeType.ORGANIZATION, organizationId, organizationId).ifPresent(applicable::add);
        }
        return merge(applicable);
    }

    private Optional<DataProtectionPolicy> find(DataProtectionScopeType scope, UUID organizationId, UUID scopeId) {
        return policies.findByScopeTypeAndOrganizationIdAndScopeId(scope, organizationId, scopeId);
    }

    public EffectiveDataProtectionPolicy merge(List<DataProtectionPolicy> applicable) {
        DataProtectionMode mode = DataProtectionMode.OFF;
        DataProtectionLevel level = DataProtectionLevel.RELAXED;
        DataProtectionAction action = DataProtectionAction.ALLOW;
        boolean failover = true, secrets = false, pii = false, financial = false, confidential = false, media = false;
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        List<String> scopes = new ArrayList<>();
        for (DataProtectionPolicy policy : applicable == null ? List.<DataProtectionPolicy>of() : applicable) {
            mode = strongerMode(mode, policy.getMode());
            level = strongerLevel(level, policy.getLevel());
            action = strongerAction(action, policy.getExternalAction());
            failover &= policy.isAllowExternalFailover();
            secrets |= policy.isDetectSecrets();
            pii |= policy.isDetectPii();
            financial |= policy.isDetectFinancial();
            confidential |= policy.isDetectConfidential();
            media |= policy.isDetectMedia();
            patterns.addAll(readPatterns(policy.getCustomPatternsJson()));
            scopes.add(policy.getScopeType().name());
        }
        if (applicable == null || applicable.isEmpty()) return EffectiveDataProtectionPolicy.defaults();
        return new EffectiveDataProtectionPolicy(mode, level, action, failover, secrets, pii, financial,
                confidential, media, List.copyOf(patterns), List.copyOf(scopes));
    }

    private EffectiveDataProtectionPolicy strengthen(EffectiveDataProtectionPolicy policy, String requested) {
        if (requested == null || requested.isBlank()) return policy;
        DataProtectionMode mode = policy.mode();
        DataProtectionLevel level = policy.level();
        DataProtectionAction action = policy.externalAction();
        boolean failover = policy.allowExternalFailover();
        for (String raw : requested.split(",")) {
            String value = raw.trim().toUpperCase();
            switch (value) {
                case "MONITOR" -> mode = strongerMode(mode, DataProtectionMode.MONITOR);
                case "ENFORCE", "STRICT" -> {
                    mode = DataProtectionMode.ENFORCE;
                    level = strongerLevel(level, DataProtectionLevel.STRICT);
                }
                case "LOCAL_ONLY", "LOCAL-ONLY" -> {
                    mode = DataProtectionMode.ENFORCE;
                    action = strongerAction(action, DataProtectionAction.LOCAL_ONLY);
                    failover = false;
                }
                case "BLOCK" -> {
                    mode = DataProtectionMode.ENFORCE;
                    action = DataProtectionAction.BLOCK;
                    failover = false;
                }
                default -> { }
            }
        }
        return new EffectiveDataProtectionPolicy(mode, level, action, failover, policy.detectSecrets(),
                policy.detectPii(), policy.detectFinancial(), policy.detectConfidential(), policy.detectMedia(),
                policy.customPatterns(), policy.appliedScopes());
    }

    private DataProtectionMode strongerMode(DataProtectionMode left, DataProtectionMode right) {
        return modeRank(right) > modeRank(left) ? right : left;
    }
    private int modeRank(DataProtectionMode value) { return value == DataProtectionMode.ENFORCE ? 2 : value == DataProtectionMode.MONITOR ? 1 : 0; }
    private DataProtectionLevel strongerLevel(DataProtectionLevel left, DataProtectionLevel right) {
        return levelRank(right) > levelRank(left) ? right : left;
    }
    private int levelRank(DataProtectionLevel value) { return value == DataProtectionLevel.STRICT ? 3 : value == DataProtectionLevel.BALANCED || value == DataProtectionLevel.CUSTOM ? 2 : 1; }
    private DataProtectionAction strongerAction(DataProtectionAction left, DataProtectionAction right) {
        return actionRank(right) > actionRank(left) ? right : left;
    }
    private int actionRank(DataProtectionAction value) { return value == DataProtectionAction.BLOCK ? 3 : value == DataProtectionAction.LOCAL_ONLY ? 2 : value == DataProtectionAction.REDACT ? 1 : 0; }

    private void validatePatterns(String json) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode values = node != null && node.isArray() ? node : node == null ? null : node.path("patterns");
            if (values == null || !values.isArray() || values.size() > 32) throw invalidPattern();
            for (JsonNode value : values) {
                if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 256) {
                    throw invalidPattern();
                }
                Pattern.compile(value.asText());
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidPattern();
        }
    }

    private ApiException invalidPattern() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATA_PROTECTION_PATTERN",
                "customPatternsJson must contain at most 32 regular expressions, each no longer than 256 characters.");
    }
    private List<String> readPatterns(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode values = node.isArray() ? node : node.path("patterns");
            if (!values.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode value : values) if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText());
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    public List<DataProtectionPolicy> list(UUID organizationId) {
        return policies.findByOrganizationIdOrderByScopeTypeAsc(organizationId);
    }

    @Transactional(readOnly = true)
    public Optional<DataProtectionPolicy> stored(UUID organizationId, DataProtectionScopeType scope, UUID scopeId) {
        if (scope == DataProtectionScopeType.PLATFORM) return policies.findByScopeTypeAndOrganizationIdIsNullAndScopeIdIsNull(scope);
        return find(scope, organizationId, scopeId);
    }

    @Transactional
    public DataProtectionPolicy save(UUID organizationId, DataProtectionScopeType scope, UUID scopeId,
                                     PolicyValues values) {
        validatePatterns(values.customPatternsJson());
        DataProtectionPolicy policy = stored(organizationId, scope, scopeId)
                .orElseGet(() -> new DataProtectionPolicy(scope == DataProtectionScopeType.PLATFORM ? null : organizationId,
                        scope, scope == DataProtectionScopeType.ORGANIZATION ? organizationId : scopeId,
                        values.mode(), values.level(), values.externalAction(), values.allowExternalFailover(),
                        values.detectSecrets(), values.detectPii(), values.detectFinancial(), values.detectConfidential(),
                        values.detectMedia(), values.customPatternsJson()));
        policy.configure(values.mode(), values.level(), values.externalAction(), values.allowExternalFailover(),
                values.detectSecrets(), values.detectPii(), values.detectFinancial(), values.detectConfidential(),
                values.detectMedia(), values.customPatternsJson());
        return policies.save(policy);
    }

    public record PolicyValues(DataProtectionMode mode, DataProtectionLevel level,
                               DataProtectionAction externalAction, boolean allowExternalFailover,
                               boolean detectSecrets, boolean detectPii, boolean detectFinancial,
                               boolean detectConfidential, boolean detectMedia, String customPatternsJson) {
        public PolicyValues {
            mode = mode == null ? DataProtectionMode.OFF : mode;
            level = level == null ? DataProtectionLevel.RELAXED : level;
            externalAction = externalAction == null ? DataProtectionAction.ALLOW : externalAction;
            customPatternsJson = customPatternsJson == null || customPatternsJson.isBlank() ? "[]" : customPatternsJson;
        }
    }
}
