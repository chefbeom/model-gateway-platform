package com.aiconnect.llmgateway.dataprotection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small, deterministic pre-flight scanner. It never returns the matched value;
 * only a classification and count are retained for audit and diagnostics.
 */
@Component
public class DataProtectionScanner {
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(sk-[A-Za-z0-9]{20,}|sk_llmg_[A-Za-z0-9._-]+|AKIA[0-9A-Z]{16}|-----BEGIN (?:RSA|EC|OPENSSH|PRIVATE) KEY-----|(?:api[_-]?key|secret|password|token)\\s*[:=]\\s*[^\\s,;]{8,})");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?:\\+?82[- .]?)?0?1[016789][- .]?\\d{3,4}[- .]?\\d{4}");
    private static final Pattern RESIDENT_NUMBER = Pattern.compile("\\b\\d{6}-?[1-4]\\d{6}\\b");
    private static final Pattern CARD_NUMBER = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    private static final Pattern CONFIDENTIAL = Pattern.compile("(?i)(confidential|internal[- ]only|trade secret|private|\\uAE30\\uBC00|\\uB300\\uC678\\uBE44|\\uC601\\uC5C5\\uBE44\\uBC00)");
    private static final Pattern MEDIA_DATA = Pattern.compile("(?i)^data:(?:image|audio|video|application/pdf|application/octet-stream)/");
    private static final Set<String> STRUCTURAL_FIELDS = Set.of(
            "model", "stream", "temperature", "top_p", "top_k", "n", "seed", "max_tokens",
            "max_completion_tokens", "presence_penalty", "frequency_penalty", "logprobs",
            "parallel_tool_calls", "user", "service_tier"
    );

    private final ObjectMapper objectMapper;

    public DataProtectionScanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DataProtectionScanResult scan(JsonNode request, EffectiveDataProtectionPolicy policy) {
        EnumSet<DataClassification> classifications = EnumSet.noneOf(DataClassification.class);
        EnumMap<DataClassification, Integer> counts = new EnumMap<>(DataClassification.class);
        if (request != null && policy != null) scanNode(request, "", policy, classifications, counts);
        return new DataProtectionScanResult(classifications, counts);
    }

    private void scanNode(JsonNode node, String fieldName, EffectiveDataProtectionPolicy policy,
                          EnumSet<DataClassification> classifications,
                          EnumMap<DataClassification, Integer> counts) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            String value = node.textValue();
            if (value == null || value.isBlank()) return;
            String bounded = value.length() > 65_536 ? value.substring(0, 65_536) : value;
            if (policy.detectSecrets() && SECRET.matcher(bounded).find()) add(DataClassification.SECRET, classifications, counts);
            if (policy.detectPii() && (EMAIL.matcher(bounded).find() || PHONE.matcher(bounded).find()
                    || RESIDENT_NUMBER.matcher(bounded).find())) add(DataClassification.PII, classifications, counts);
            if (policy.detectFinancial() && CARD_NUMBER.matcher(bounded).find()) add(DataClassification.FINANCIAL, classifications, counts);
            if (policy.detectConfidential() && CONFIDENTIAL.matcher(bounded).find()) add(DataClassification.CONFIDENTIAL, classifications, counts);
            if (policy.detectMedia() && isMedia(fieldName, bounded)) add(DataClassification.UNKNOWN_MEDIA, classifications, counts);
            for (String custom : policy.customPatterns()) {
                try {
                    if (!custom.isBlank() && Pattern.compile(custom).matcher(bounded).find()) {
                        add(DataClassification.CONFIDENTIAL, classifications, counts);
                        break;
                    }
                } catch (RuntimeException ignored) {
                    // A malformed administrator pattern must not break an AI request.
                }
            }
            return;
        }
        if (node.isContainerNode()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (STRUCTURAL_FIELDS.contains(field.getKey())) continue;
                scanNode(field.getValue(), field.getKey(), policy, classifications, counts);
            }
            if (node.isArray()) {
                for (JsonNode child : node) scanNode(child, fieldName, policy, classifications, counts);
            }
        }
    }

    private boolean isMedia(String fieldName, String value) {
        if (MEDIA_DATA.matcher(value).find()) return true;
        String field = fieldName == null ? "" : fieldName.toLowerCase();
        if (!(field.contains("image") || field.contains("audio") || field.contains("video") || field.contains("file"))) return false;
        try {
            URI uri = URI.create(value);
            return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void add(DataClassification classification, EnumSet<DataClassification> classifications,
                     EnumMap<DataClassification, Integer> counts) {
        classifications.add(classification);
        counts.merge(classification, 1, Integer::sum);
    }
}
