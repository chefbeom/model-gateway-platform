package com.aiconnect.llmgateway.dataprotection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Small, deterministic pre-flight scanner. It never returns the matched value;
 * only a classification and count are retained for audit and diagnostics.
 */
@Component
public class DataProtectionScanner {
    private static final int MAX_NODES = 10_000;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_TEXT_CHARS = 2_000_000;
    private static final int MAX_CUSTOM_REGEX_STEPS = 100_000;
    private static final int MAX_PATTERN_CACHE_SIZE = 256;
    private static final class Budget { int nodes; int chars; boolean exhausted; }
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(sk-(?:proj-|svcacct-|admin-|ant-)?[A-Za-z0-9_-]{20,}|sk_llmg_[A-Za-z0-9._-]+|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]+|AIza[0-9A-Za-z_-]{35}|-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----|(?:api[_-]?key|secret|password|token)\\s*[:=]\\s*[^\\s,;]{8,})");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]++@[A-Z0-9-]++(?:\\.[A-Z0-9-]++)*\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?:\\+?82[- .]?)?0?1[016789][- .]?\\d{3,4}[- .]?\\d{4}");
    private static final Pattern RESIDENT_NUMBER = Pattern.compile("\\b\\d{6}-?[1-4]\\d{6}\\b");
    private static final Pattern CARD_NUMBER = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    private static final Pattern CONFIDENTIAL = Pattern.compile("(?i)(confidential|internal[- ]only|trade secret|private|\\uAE30\\uBC00|\\uB300\\uC678\\uBE44|\\uC601\\uC5C5\\uBE44\\uBC00)");
    private static final Pattern MEDIA_DATA = Pattern.compile("(?i)^data:(?:(?:image|audio|video)/[^;,]+|application/(?:pdf|octet-stream))(?:;|,)");
    private static final Pattern STRICT_BASE64_MEDIA_DATA = Pattern.compile(
            "(?i)^data:(?:(?:image|audio|video)/[a-z0-9.+-]+|application/(?:pdf|octet-stream));base64,[A-Za-z0-9+/=\\s]+$");
    private static final Set<String> MEDIA_DATA_FIELDS = Set.of(
            "url", "file", "image", "audio", "video", "data", "attachment", "image_url", "file_data"
    );
    private static final Set<String> STRUCTURAL_FIELDS = Set.of(
            "model", "stream", "temperature", "top_p", "top_k", "n", "seed", "max_tokens",
            "max_completion_tokens", "presence_penalty", "frequency_penalty", "logprobs",
            "parallel_tool_calls", "service_tier"
    );

    private final ObjectMapper objectMapper;
    private final Map<String, Pattern> customPatternCache = new ConcurrentHashMap<>();

    public DataProtectionScanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DataProtectionScanResult scan(JsonNode request, EffectiveDataProtectionPolicy policy) {
        EnumSet<DataClassification> classifications = EnumSet.noneOf(DataClassification.class);
        EnumMap<DataClassification, Integer> counts = new EnumMap<>(DataClassification.class);
        if (request != null && policy != null) scanNode(request, "", policy, classifications, counts, new Budget(), 0);
        return new DataProtectionScanResult(classifications, counts);
    }

    private void scanNode(JsonNode node, String fieldName, EffectiveDataProtectionPolicy policy,
                          EnumSet<DataClassification> classifications,
                          EnumMap<DataClassification, Integer> counts, Budget budget, int depth) {
        if (node == null || node.isNull()) return;
        if (budget.exhausted) return;
        if (++budget.nodes > MAX_NODES || depth > MAX_DEPTH) {
            budget.exhausted = true; add(DataClassification.SCAN_INCOMPLETE, classifications, counts); return;
        }
        if (node.isTextual()) {
            String value = node.textValue();
            if (value == null || value.isBlank()) return;
            // Inline base64 media in explicit media fields is binary; never skip general text fields.
            if (isMediaDataField(fieldName) && STRICT_BASE64_MEDIA_DATA.matcher(value).matches()) {
                if (policy.detectMedia()) add(DataClassification.UNKNOWN_MEDIA, classifications, counts);
                return;
            }
            if ((long) budget.chars + value.length() > MAX_TEXT_CHARS) {
                budget.exhausted = true; add(DataClassification.SCAN_INCOMPLETE, classifications, counts); return;
            }
            budget.chars += value.length();
            String bounded = value.length() > 65_536 ? value.substring(0, 65_536) : value;
            if (value.length() > 65_536) add(DataClassification.SCAN_INCOMPLETE, classifications, counts);
            if (policy.detectSecrets() && SECRET.matcher(bounded).find()) add(DataClassification.SECRET, classifications, counts);
            if (policy.detectPii() && (EMAIL.matcher(bounded).find() || PHONE.matcher(bounded).find()
                    || RESIDENT_NUMBER.matcher(bounded).find())) add(DataClassification.PII, classifications, counts);
            if (policy.detectFinancial() && CARD_NUMBER.matcher(bounded).find()) add(DataClassification.FINANCIAL, classifications, counts);
            if (policy.detectConfidential() && CONFIDENTIAL.matcher(bounded).find()) add(DataClassification.CONFIDENTIAL, classifications, counts);
            if (policy.detectMedia() && isMedia(fieldName, bounded)) add(DataClassification.UNKNOWN_MEDIA, classifications, counts);
            int[] remainingCustomSteps = new int[]{MAX_CUSTOM_REGEX_STEPS};
            boolean confidentialRecorded = classifications.contains(DataClassification.CONFIDENTIAL);
            for (String custom : policy.customPatterns()) {
                if (custom == null || custom.isBlank()) continue;
                try {
                    Pattern compiled = compileCached(custom);
                    if (compiled.matcher(new BoundedCharSequence(bounded, remainingCustomSteps)).find()) {
                        if (!confidentialRecorded) {
                            add(DataClassification.CONFIDENTIAL, classifications, counts);
                            confidentialRecorded = true;
                        }
                    }
                } catch (StepBudgetExceededException stepLimit) {
                    add(DataClassification.SCAN_INCOMPLETE, classifications, counts);
                    break;
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
                if (fieldName.isEmpty() && STRUCTURAL_FIELDS.contains(field.getKey())) continue;
                scanNode(field.getValue(), fieldName + "." + field.getKey(), policy, classifications, counts, budget, depth + 1);
                if (budget.exhausted) break;
            }
            if (node.isArray()) {
                for (JsonNode child : node) {
                    scanNode(child, fieldName, policy, classifications, counts, budget, depth + 1);
                    if (budget.exhausted) break;
                }
            }
        }
    }

    private Pattern compileCached(String regex) {
        if (customPatternCache.size() >= MAX_PATTERN_CACHE_SIZE && !customPatternCache.containsKey(regex)) {
            customPatternCache.clear();
        }
        return customPatternCache.computeIfAbsent(regex, Pattern::compile);
    }

    private boolean isMediaDataField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) return false;
        int dot = fieldName.lastIndexOf('.');
        String leaf = (dot >= 0 ? fieldName.substring(dot + 1) : fieldName).toLowerCase(Locale.ROOT);
        return MEDIA_DATA_FIELDS.contains(leaf);
    }

    private static final class StepBudgetExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class BoundedCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final int[] remainingSteps;

        private BoundedCharSequence(CharSequence delegate, int maxSteps) {
            this(delegate, new int[]{maxSteps});
        }

        private BoundedCharSequence(CharSequence delegate, int[] remainingSteps) {
            this.delegate = delegate;
            this.remainingSteps = remainingSteps;
        }

        @Override public int length() { return delegate.length(); }

        @Override public char charAt(int index) {
            if (--remainingSteps[0] < 0) throw new StepBudgetExceededException();
            return delegate.charAt(index);
        }

        @Override public CharSequence subSequence(int start, int end) {
            return new BoundedCharSequence(delegate.subSequence(start, end), remainingSteps);
        }

        @Override public String toString() { return delegate.toString(); }
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
