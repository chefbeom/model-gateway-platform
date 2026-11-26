package com.aiconnect.llmgateway.dataprotection;

import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.repository.ExternalProviderRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.runtime.InferenceRuntimeClient;
import com.aiconnect.llmgateway.runtime.OpenAiRuntimeClient;
import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.aiconnect.llmgateway.security.RuntimeTrustAttestation;
import com.aiconnect.llmgateway.security.RuntimeTrustAttestationRepository;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs reversible format-preserving pseudonymization on outbound LLM requests
 * and deterministic unmasking via an in-memory per-request vault on return.
 */
@Component
public class DataProtectionRedactor {
    private static final Logger log = LoggerFactory.getLogger(DataProtectionRedactor.class);

    private static final Pattern RESIDENT_NUMBER = Pattern.compile("\\b\\d{6}-?[1-4]\\d{6}\\b");
    private static final Pattern PHONE = Pattern.compile("(?:\\+?82[- .]?)?0?1[016789][- .]?\\d{3,4}[- .]?\\d{4}");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]++@[A-Z0-9-]++(?:\\.[A-Z0-9-]++)*\\.[A-Z]{2,}\\b");
    private static final Pattern CARD_NUMBER = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(sk-(?:proj-|svcacct-|admin-|ant-)?[A-Za-z0-9_-]{20,}|sk_llmg_[A-Za-z0-9._-]+|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]+|AIza[0-9A-Za-z_-]{35}|-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----|(?:api[_-]?key|secret|password|token)\\s*[:=]\\s*[^\\s,;]{8,})");
    private static final Pattern CONFIDENTIAL = Pattern.compile("(?i)(confidential|internal[- ]only|trade secret|private|\\uAE30\\uBC00|\\uB300\\uC678\\uBE44|\\uC601\\uC5C5\\uBE44\\uBC00)");
    private static final Pattern CONTEXT_NAME_BEFORE_ID = Pattern.compile("([\\uAC00-\\uD7A3]{2,4})(?=\\s+(?:\\d{6}-?[1-4]\\d{6}|(?:\\+?82[- .]?)?0?1[016789][- .]?\\d{3,4}[- .]?\\d{4}))");
    private static final Pattern LABELED_KOREAN_NAME = Pattern.compile("(?<=(?:이름|성명|고객명|학생|작성자)\\s*[:：]\\s*)([\\uAC00-\\uD7A3]{2,4})");
    private static final Set<String> NAME_STOPWORDS = Set.of(
            "어제", "오늘", "내일", "모레", "학교", "회사", "번호", "연락처", "전화번호",
            "주민번호", "주민등록번호", "회원", "고객", "본인", "담당자", "사용자", "선생님", "학생", "이름"
    );
    private static final Set<String> STRUCTURAL_FIELDS = Set.of(
            "model", "stream", "temperature", "top_p", "top_k", "n", "seed", "max_tokens",
            "max_completion_tokens", "presence_penalty", "frequency_penalty", "logprobs",
            "parallel_tool_calls", "service_tier", "stream_options",
            "role", "type", "name", "tool_call_id", "id", "index", "finish_reason"
    );
    private static final Set<String> MEDIA_DATA_FIELDS = Set.of(
            "url", "file", "image", "audio", "video", "data", "attachment", "image_url", "file_data"
    );
    private static final Pattern STRICT_BASE64_MEDIA_DATA = Pattern.compile(
            "(?i)^data:(?:(?:image|audio|video)/[a-z0-9.+-]+|application/(?:pdf|octet-stream));base64,[A-Za-z0-9+/=\\s]+$");
    private static final int MAX_CUSTOM_REGEX_STEPS = 100_000;
    private static final List<String> KOREAN_NAME_SURROGATES = List.of("김가명", "이가명", "박가명");

    private final ObjectMapper objectMapper;
    @Autowired(required = false) private ModelDeploymentRepository deployments;
    @Autowired(required = false) private RuntimeEndpointRepository endpoints;
    @Autowired(required = false) private ExternalProviderRepository providers;
    @Autowired(required = false) private InferenceNodeRepository nodes;
    @Autowired(required = false) private RuntimeTrustAttestationRepository trustAttestations;
    @Autowired(required = false) private InferenceRuntimeClient runtimeClient;
    @Autowired(required = false) private OpenAiRuntimeClient openAiClient;

    @Autowired
    public DataProtectionRedactor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DataProtectionRedactor(ObjectMapper objectMapper,
                                  ModelDeploymentRepository deployments,
                                  RuntimeEndpointRepository endpoints,
                                  ExternalProviderRepository providers,
                                  InferenceNodeRepository nodes,
                                  RuntimeTrustAttestationRepository trustAttestations,
                                  InferenceRuntimeClient runtimeClient,
                                  OpenAiRuntimeClient openAiClient) {
        this.objectMapper = objectMapper;
        this.deployments = deployments;
        this.endpoints = endpoints;
        this.providers = providers;
        this.nodes = nodes;
        this.trustAttestations = trustAttestations;
        this.runtimeClient = runtimeClient;
        this.openAiClient = openAiClient;
    }

    public RedactionVault redactRequest(ObjectNode request, EffectiveDataProtectionPolicy policy,
                                        LlmService service, boolean invokeMaskerLlm) {
        return redactRequest(request, policy, service, invokeMaskerLlm, true);
    }

    public RedactionVault redactRequest(ObjectNode request, EffectiveDataProtectionPolicy policy,
                                        LlmService service, boolean invokeMaskerLlm, boolean allowExternalMasker) {
        RedactionVault vault = new RedactionVault("DETERMINISTIC_REGEX");
        if (request == null) return vault;
        EffectiveDataProtectionPolicy effective = policy == null ? EffectiveDataProtectionPolicy.defaults() : policy;
        boolean maskPersonNames = service == null || service.isMaskPersonNames();
        Counters counters = new Counters();

        redactNode(request, "", effective, maskPersonNames, vault, counters, 0);

        if (invokeMaskerLlm && service != null && service.getMaskerDeploymentId() != null) {
            applyTrustedMaskerLlm(request, service, vault, allowExternalMasker);
        }
        return vault;
    }

    public void assertTrustedMaskerDeployment(UUID organizationId, UUID deploymentId) {
        if (deploymentId == null) return;
        if (deployments == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNTRUSTED_MASKER_DEPLOYMENT",
                    "Masker deployment validation is unavailable.");
        }
        ModelDeployment deployment = deployments.findById(deploymentId)
                .filter(item -> item.isEnabled() && !item.isArchived())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "UNTRUSTED_MASKER_DEPLOYMENT",
                        "선택한 마스킹 모델을 찾을 수 없거나 비활성 상태입니다."));
        if (deployment.isExternal()) {
            ExternalProvider provider = providers == null || deployment.getExternalProviderId() == null ? null
                    : providers.findById(deployment.getExternalProviderId()).orElse(null);
            if (provider == null || (organizationId != null && !organizationId.equals(provider.getOrganizationId()))
                    || !provider.isEnabled() || !provider.isZeroRetentionVerified()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNTRUSTED_MASKER_DEPLOYMENT",
                        "외부 유출 없음(Zero-Retention)이 확인되지 않은 외부 API 모델은 마스킹 작업에 사용할 수 없습니다.");
            }
            return;
        }
        RuntimeEndpoint endpoint = endpoints == null || deployment.getRuntimeEndpointId() == null ? null
                : endpoints.findById(deployment.getRuntimeEndpointId()).orElse(null);
        if (endpoint == null || !endpoint.isEnabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNTRUSTED_MASKER_DEPLOYMENT",
                    "선택한 로컬/런타임 마스킹 엔드포인트를 사용할 수 없습니다.");
        }
        if (organizationId != null && nodes != null) {
            InferenceNode node = nodes.findById(endpoint.getNodeId()).orElse(null);
            if (node == null || !organizationId.equals(node.getOrganizationId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNTRUSTED_MASKER_DEPLOYMENT",
                        "다른 조직의 모델은 마스킹 작업에 사용할 수 없습니다.");
            }
        }
        if (trustAttestations != null) {
            RuntimeTrustAttestation att = trustAttestations.findById(endpoint.getId()).orElse(null);
            if (att != null) {
                boolean expiredOrRevoked = !att.validFor(endpoint.getBaseUrl(), Instant.now());
                if (att.getClassification() == RuntimeTrustAttestation.Classification.EXTERNAL_UNVERIFIED
                        || expiredOrRevoked) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "UNTRUSTED_MASKER_DEPLOYMENT",
                            "외부 미확인 또는 신뢰 철회·만료된 Runtime은 마스킹 작업에 사용할 수 없습니다.");
                }
            }
        }
    }

    public boolean isTrustedMaskerDeployment(UUID organizationId, UUID deploymentId) {
        try {
            assertTrustedMaskerDeployment(organizationId, deploymentId);
            return true;
        } catch (ApiException exception) {
            return false;
        }
    }

    private void redactNode(JsonNode node, String fieldPath, EffectiveDataProtectionPolicy policy,
                            boolean maskPersonNames, RedactionVault vault, Counters counters, int depth) {
        if (node == null || node.isNull() || depth > 64) return;
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<Map.Entry<String, JsonNode>> snapshot = new ArrayList<>();
            fields.forEachRemaining(snapshot::add);
            for (Map.Entry<String, JsonNode> entry : snapshot) {
                String key = entry.getKey();
                if (fieldPath.isEmpty() && STRUCTURAL_FIELDS.contains(key)) continue;
                String nextPath = fieldPath.isEmpty() ? key : fieldPath + "." + key;
                JsonNode val = entry.getValue();
                if (val.isTextual()) {
                    if (isMediaDataField(nextPath) && STRICT_BASE64_MEDIA_DATA.matcher(val.asText()).matches()) continue;
                    String updated = redactText(val.asText(), policy, maskPersonNames, vault, counters);
                    if (!updated.equals(val.asText())) {
                        object.put(key, updated);
                    }
                } else {
                    redactNode(val, nextPath, policy, maskPersonNames, vault, counters, depth + 1);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode item = array.get(i);
                if (item.isTextual()) {
                    String updated = redactText(item.asText(), policy, maskPersonNames, vault, counters);
                    if (!updated.equals(item.asText())) {
                        array.set(i, updated);
                    }
                } else {
                    redactNode(item, fieldPath, policy, maskPersonNames, vault, counters, depth + 1);
                }
            }
        }
    }

    private String redactText(String text, EffectiveDataProtectionPolicy policy,
                              boolean maskPersonNames, RedactionVault vault, Counters counters) {
        if (text == null || text.isBlank()) return text;
        String current = text;

        if (policy.detectPii()) {
            if (maskPersonNames) {
                current = replacePattern(current, CONTEXT_NAME_BEFORE_ID, match -> {
                    if (NAME_STOPWORDS.contains(match)) return match;
                    return vault.register("PII_NAME", match,
                            nextNameSurrogate(counters.names.incrementAndGet()), "REGEX");
                }, vault);
                current = replacePattern(current, LABELED_KOREAN_NAME, match -> {
                    if (NAME_STOPWORDS.contains(match)) return match;
                    return vault.register("PII_NAME", match,
                            nextNameSurrogate(counters.names.incrementAndGet()), "REGEX");
                }, vault);
            }
            current = replacePattern(current, RESIDENT_NUMBER, match -> {
                String existing = vault.surrogateFor(match);
                if (existing != null) return existing;
                int n = counters.resident.incrementAndGet();
                String surrogate = n == 1 ? "123456-7890123" : String.format("123456-%07d", 7890122 + n);
                return vault.register("PII_RESIDENT_NUMBER", match, surrogate, "REGEX");
            }, vault);
            current = replacePattern(current, PHONE, match -> {
                String existing = vault.surrogateFor(match);
                if (existing != null) return existing;
                int n = counters.phone.incrementAndGet();
                String surrogate = n == 1 ? "010-0000-0000" : String.format("010-0000-%04d", n - 1);
                return vault.register("PII_PHONE", match, surrogate, "REGEX");
            }, vault);
            current = replacePattern(current, EMAIL, match -> {
                String existing = vault.surrogateFor(match);
                if (existing != null) return existing;
                int n = counters.email.incrementAndGet();
                return vault.register("PII_EMAIL", match, String.format("masked%04d@example.com", n - 1), "REGEX");
            }, vault);
        }

        if (policy.detectFinancial()) {
            current = replacePattern(current, CARD_NUMBER, match -> {
                if (vault.containsSurrogate(match)) return match;
                String existing = vault.surrogateFor(match);
                if (existing != null) return existing;
                int n = counters.card.incrementAndGet();
                String surrogate = n == 1 ? "0000-0000-0000-0000" : String.format("0000-0000-0000-%04d", n - 1);
                return vault.register("FINANCIAL_CARD", match, surrogate, "REGEX");
            }, vault);
        }

        if (policy.detectSecrets()) {
            current = replacePattern(current, SECRET, match -> {
                String existing = vault.surrogateFor(match);
                if (existing != null) return existing;
                int n = counters.secret.incrementAndGet();
                return vault.register("SECRET", match, "[REDACTED_SECRET_" + n + "]", "REGEX");
            }, vault);
        }

        if (policy.detectConfidential()) {
            current = replacePattern(current, CONFIDENTIAL, match -> {
                String existing = vault.surrogateFor(match);
                if (existing != null) return existing;
                int n = counters.confidential.incrementAndGet();
                return vault.register("CONFIDENTIAL", match, "[REDACTED_CONFIDENTIAL_" + n + "]", "REGEX");
            }, vault);
            for (String custom : policy.customPatterns()) {
                if (custom == null || custom.isBlank()) continue;
                try {
                    Pattern compiled = Pattern.compile(custom);
                    current = replacePatternBounded(current, compiled, match -> {
                        if (vault.containsSurrogate(match)) return match;
                        String existing = vault.surrogateFor(match);
                        if (existing != null) return existing;
                        int n = counters.confidential.incrementAndGet();
                        return vault.register("CONFIDENTIAL", match, "[REDACTED_CONFIDENTIAL_" + n + "]", "REGEX");
                    }, vault, MAX_CUSTOM_REGEX_STEPS + current.length());
                } catch (RuntimeException ignored) { }
            }
        }
        return current;
    }

    private String replacePattern(String input, Pattern pattern, Function<String, String> mapper, RedactionVault vault) {
        return replacePatternBounded(input, pattern, mapper, vault, Integer.MAX_VALUE);
    }

    private String replacePatternBounded(String input, Pattern pattern, Function<String, String> mapper,
                                         RedactionVault vault, int maxSteps) {
        CharSequence seq = maxSteps == Integer.MAX_VALUE ? input : new BoundedCharSequence(input, maxSteps);
        Matcher matcher = pattern.matcher(seq);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String matched = matcher.group();
            if (matched == null || matched.isEmpty()) continue;
            String replacement = vault.isSurrogateValue(matched) ? matched : mapper.apply(matched);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private void applyTrustedMaskerLlm(ObjectNode request, LlmService service, RedactionVault vault, boolean allowExternalMasker) {
        UUID deploymentId = service.getMaskerDeploymentId();
        if (deploymentId == null || deployments == null) return;
        if (!isTrustedMaskerDeployment(service.getOrganizationId(), deploymentId)) return;
        ModelDeployment masker = deployments.findById(deploymentId).orElse(null);
        if (masker == null) return;
        if (masker.isExternal() && !allowExternalMasker) return;

        String collectedText = collectPromptText(request);
        if (collectedText.isBlank()) return;

        ObjectNode maskerRequest = objectMapper.createObjectNode();
        maskerRequest.put("model", masker.getProviderModelId());
        maskerRequest.put("stream", false);
        maskerRequest.put("temperature", 0);
        maskerRequest.put("max_tokens", 256);
        ArrayNode messages = maskerRequest.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", "You are a strict PII contextual redactor. Identify any remaining unmasked personal names, specific street addresses, or account numbers in the user's text while preserving general context (dates, schools, actions). Do NOT touch existing surrogates like 123456-7890123 or 010-0000-0000. Reply ONLY with compact JSON: {\"replacements\":[{\"original\":\"...\",\"surrogate\":\"...\"}]}. If none, reply {\"replacements\":[]}.");
        messages.addObject()
                .put("role", "user")
                .put("content", collectedText.length() > 4000 ? collectedText.substring(0, 4000) : collectedText);

        RuntimeResult result = null;
        try {
            if (masker.isExternal() && providers != null && openAiClient != null) {
                ExternalProvider provider = providers.findById(masker.getExternalProviderId()).orElse(null);
                if (provider != null && provider.isZeroRetentionVerified()) {
                    vault.setMaskerUsed("EXTERNAL_ZERO_RETENTION:" + masker.getDisplayName());
                    result = openAiClient.chatCompletion(provider, maskerRequest, false);
                }
            } else if (!masker.isExternal() && endpoints != null && runtimeClient != null) {
                RuntimeEndpoint endpoint = endpoints.findById(masker.getRuntimeEndpointId()).orElse(null);
                if (endpoint != null) {
                    vault.setMaskerUsed("LOCAL_RUNTIME:" + masker.getDisplayName());
                    result = runtimeClient.chatCompletion(endpoint, maskerRequest);
                }
            }
        } catch (Exception ex) {
            log.debug("Trusted masker LLM call skipped cleanly: {}", ex.getMessage());
            return;
        }
        if (result == null || !result.isSuccessful()) return;

        String content = result.body().path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) return;
        try {
            int firstBrace = content.indexOf('{');
            int lastBrace = content.lastIndexOf('}');
            if (firstBrace < 0 || lastBrace <= firstBrace) return;
            JsonNode parsed = objectMapper.readTree(content.substring(firstBrace, lastBrace + 1));
            JsonNode replacements = parsed.path("replacements");
            if (!replacements.isArray()) return;
            int ctxSeq = 1;
            for (JsonNode item : replacements) {
                String orig = item.path("original").asText("").trim();
                String sur = item.path("surrogate").asText("").trim();
                if (orig.length() < 2 || orig.length() > 200 || sur.isEmpty() || sur.length() > 200) continue;
                if (orig.equals(sur) || vault.isSurrogateValue(orig) || NAME_STOPWORDS.contains(orig)) continue;
                if (!collectedText.contains(orig) || orig.length() > Math.max(24, collectedText.length() * 6 / 10)) continue;
                if (sur.length() < 2 || vault.isSurrogateValue(sur) || collectedText.contains(sur)) {
                    while (vault.isSurrogateValue("[MASKED_CTX_" + ctxSeq + "]")
                            || collectedText.contains("[MASKED_CTX_" + ctxSeq + "]")) {
                        ctxSeq++;
                    }
                    sur = "[MASKED_CTX_" + (ctxSeq++) + "]";
                }
                String registered = vault.register("CONTEXT_LLM", orig, sur, masker.getDisplayName());
                replaceLiteralInRequestNodes(request, orig, registered);
            }
        } catch (Exception ignored) {
            // Non-JSON responses from the masker worker must never abort deterministic protection.
        }
    }

    private String collectPromptText(ObjectNode request) {
        StringBuilder sb = new StringBuilder();
        JsonNode messages = request.path("messages");
        if (messages.isArray()) {
            for (JsonNode msg : messages) {
                appendContentText(msg.path("content"), sb);
            }
        }
        if (sb.isEmpty()) {
            appendContentText(request.path("input"), sb);
        }
        return sb.toString();
    }

    private static void appendContentText(JsonNode content, StringBuilder sb) {
        if (content == null || content.isNull() || content.isMissingNode()) return;
        if (content.isTextual()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(content.asText());
            return;
        }
        if (content.isArray()) {
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(part.asText());
                } else if (part.isObject()) {
                    JsonNode text = part.path("text");
                    if (text.isTextual()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(text.asText());
                    } else if (part.has("content")) {
                        appendContentText(part.path("content"), sb);
                    }
                }
            }
        }
    }

    private void replaceLiteralInRequestNodes(JsonNode node, String target, String replacement) {
        if (node == null || node.isNull()) return;
        if (node instanceof ObjectNode obj) {
            List<Map.Entry<String, JsonNode>> snapshot = new ArrayList<>();
            obj.fields().forEachRemaining(snapshot::add);
            for (Map.Entry<String, JsonNode> entry : snapshot) {
                if (STRUCTURAL_FIELDS.contains(entry.getKey())) continue;
                if (entry.getValue().isTextual()) {
                    String current = entry.getValue().asText();
                    if (isMediaDataField(entry.getKey()) && STRICT_BASE64_MEDIA_DATA.matcher(current).matches()) continue;
                    if (current.contains(target)) {
                        obj.put(entry.getKey(), current.replace(target, replacement));
                    }
                } else {
                    replaceLiteralInRequestNodes(entry.getValue(), target, replacement);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode item = arr.get(i);
                if (item.isTextual()) {
                    String current = item.asText();
                    if (current.contains(target)) {
                        arr.set(i, current.replace(target, replacement));
                    }
                } else {
                    replaceLiteralInRequestNodes(item, target, replacement);
                }
            }
        }
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

    private static String nextNameSurrogate(int index) {
        if (index >= 1 && index <= KOREAN_NAME_SURROGATES.size()) {
            return KOREAN_NAME_SURROGATES.get(index - 1);
        }
        return "사용자" + index;
    }

    private static boolean isMediaDataField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) return false;
        int dot = fieldName.lastIndexOf('.');
        String leaf = (dot >= 0 ? fieldName.substring(dot + 1) : fieldName).toLowerCase();
        return MEDIA_DATA_FIELDS.contains(leaf);
    }

    private static final class Counters {
        final AtomicInteger resident = new AtomicInteger();
        final AtomicInteger phone = new AtomicInteger();
        final AtomicInteger email = new AtomicInteger();
        final AtomicInteger name = new AtomicInteger();
        final AtomicInteger names = name;
        final AtomicInteger card = new AtomicInteger();
        final AtomicInteger secret = new AtomicInteger();
        final AtomicInteger confidential = new AtomicInteger();
    }

    public record RedactionEntry(String category, String original, String surrogate, String source) { }

    public static final class RedactionVault {
        private final List<RedactionEntry> entries = new ArrayList<>();
        private final Map<String, String> originalToSurrogate = new LinkedHashMap<>();
        private final Map<String, String> surrogateToOriginal = new LinkedHashMap<>();
        private String maskerUsed;
        private int maxSurrogateLength;

        public RedactionVault(String maskerUsed) {
            this.maskerUsed = maskerUsed;
        }

        public String register(String category, String original, String surrogate, String source) {
            String existing = originalToSurrogate.get(original);
            if (existing != null) return existing;
            String unique = surrogate;
            int seq = entries.size() + 1;
            while (surrogateToOriginal.containsKey(unique)
                    && !java.util.Objects.equals(surrogateToOriginal.get(unique), original)) {
                unique = "[MASKED_" + (seq++) + "]";
            }
            originalToSurrogate.put(original, unique);
            surrogateToOriginal.put(unique, original);
            maxSurrogateLength = Math.max(maxSurrogateLength, unique.length());
            if ("PII_RESIDENT_NUMBER".equals(category) || "PII_PHONE".equals(category)) {
                String compact = unique.replace("-", "");
                if (!compact.equals(unique) && !surrogateToOriginal.containsKey(compact)) {
                    surrogateToOriginal.put(compact, original);
                    maxSurrogateLength = Math.max(maxSurrogateLength, compact.length());
                }
            }
            entries.add(new RedactionEntry(category, original, unique, source));
            return unique;
        }

        public String surrogateFor(String original) { return originalToSurrogate.get(original); }
        public boolean isSurrogateValue(String candidate) { return surrogateToOriginal.containsKey(candidate); }
        public boolean containsSurrogate(String text) {
            if (text == null || text.isEmpty()) return false;
            for (String s : surrogateToOriginal.keySet()) {
                if (text.contains(s)) return true;
            }
            return false;
        }
        public boolean isEmpty() { return entries.isEmpty(); }
        public List<RedactionEntry> entries() { return List.copyOf(entries); }
        public String maskerUsed() { return maskerUsed; }
        public void setMaskerUsed(String maskerUsed) { this.maskerUsed = maskerUsed; }
        public int maxSurrogateLength() { return maxSurrogateLength; }

        public boolean hasPotentialPrefixTail(String text) {
            return longestPrefixTailLength(text) > 0;
        }

        public int longestPrefixTailLength(String text) {
            if (text == null || text.isEmpty() || surrogateToOriginal.isEmpty()) return 0;
            int tailMax = Math.min(text.length(), Math.max(0, maxSurrogateLength - 1));
            for (int len = tailMax; len >= 1; len--) {
                String suffix = text.substring(text.length() - len);
                for (String surrogate : surrogateToOriginal.keySet()) {
                    if (surrogate.startsWith(suffix)) return len;
                }
            }
            return 0;
        }

        public String restoreText(String text) {
            if (text == null || text.isEmpty() || surrogateToOriginal.isEmpty()) return text;
            List<String> sortedKeys = surrogateToOriginal.keySet().stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();
            StringBuilder regex = new StringBuilder();
            for (String key : sortedKeys) {
                if (regex.length() > 0) regex.append('|');
                regex.append(Pattern.quote(key));
            }
            Matcher matcher = Pattern.compile(regex.toString()).matcher(text);
            StringBuilder restored = new StringBuilder();
            while (matcher.find()) {
                String replacement = surrogateToOriginal.getOrDefault(matcher.group(), matcher.group());
                matcher.appendReplacement(restored, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(restored);
            return restored.toString();
        }

        public void restoreResponseNode(ObjectNode response) {
            if (response == null || surrogateToOriginal.isEmpty()) return;
            JsonNode choices = response.path("choices");
            if (choices.isArray()) {
                for (JsonNode choice : choices) {
                    if (!(choice instanceof ObjectNode choiceObj)) continue;
                    restoreContentField(choiceObj.path("message"));
                    restoreContentField(choiceObj.path("delta"));
                }
            }
            if (response.path("output_text").isTextual()) {
                response.put("output_text", restoreText(response.path("output_text").asText()));
            }
        }

        private void restoreContentField(JsonNode container) {
            if (!(container instanceof ObjectNode obj)) return;
            JsonNode content = obj.path("content");
            if (content.isTextual()) {
                obj.put("content", restoreText(content.asText()));
            } else if (content.isArray()) {
                for (JsonNode part : content) {
                    if (part instanceof ObjectNode partObj && partObj.path("text").isTextual()) {
                        partObj.put("text", restoreText(partObj.path("text").asText()));
                    }
                }
            }
        }
    }
}
