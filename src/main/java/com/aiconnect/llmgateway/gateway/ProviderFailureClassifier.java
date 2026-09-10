package com.aiconnect.llmgateway.gateway;

import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Optional;

/**
 * Converts provider-specific failures into stable gateway error categories.
 *
 * <p>The provider response is intentionally reduced to a bounded message.  The
 * gateway never copies the request body into an error response or diagnostic.</p>
 */
public final class ProviderFailureClassifier {
    private ProviderFailureClassifier() { }

    public enum Code {
        CONTEXT_LENGTH_EXCEEDED,
        INPUT_TOKEN_LIMIT_EXCEEDED,
        OUTPUT_TOKEN_LIMIT_EXCEEDED,
        REQUEST_FORMAT_UNSUPPORTED,
        AUTHENTICATION_FAILED,
        MODEL_NOT_FOUND,
        REQUEST_TIMEOUT,
        RATE_LIMITED,
        MODEL_AT_CAPACITY,
        UPSTREAM_UNAVAILABLE,
        UPSTREAM_REJECTED
    }

    public record Analysis(Code code, int httpStatus, String providerMessage, String message) {
        public boolean isModelSpecific() {
            return code == Code.CONTEXT_LENGTH_EXCEEDED
                    || code == Code.INPUT_TOKEN_LIMIT_EXCEEDED
                    || code == Code.OUTPUT_TOKEN_LIMIT_EXCEEDED
                    || code == Code.REQUEST_FORMAT_UNSUPPORTED;
        }

        /** A request was rejected before inference and can safely use another target. */
        public boolean isSafeToFailover() {
            return isModelSpecific();
        }

        public String codeName() { return code.name(); }
    }

    public static Analysis classify(int status, JsonNode body) {
        String providerMessage = extractMessage(body);
        return classify(status, providerMessage);
    }

    public static Analysis classify(int status, String body) {
        String providerMessage = body == null ? "" : truncate(body.trim());
        return classifyMessage(status, providerMessage);
    }

    private static Analysis classifyMessage(int status, String providerMessage) {
        String normalized = providerMessage.toLowerCase(Locale.ROOT);
        Code code;
        if (status == 401 || status == 403) code = Code.AUTHENTICATION_FAILED;
        else if (status == 404) code = Code.MODEL_NOT_FOUND;
        else if (status == 408) code = Code.REQUEST_TIMEOUT;
        else if (containsContext(normalized)) code = contextCode(normalized);
        else if (containsFormat(normalized)) code = Code.REQUEST_FORMAT_UNSUPPORTED;
        else if (status == 429) code = containsCapacity(normalized) ? Code.MODEL_AT_CAPACITY : Code.RATE_LIMITED;
        else if (status >= 500) code = containsCapacity(normalized) ? Code.MODEL_AT_CAPACITY : Code.UPSTREAM_UNAVAILABLE;
        else code = Code.UPSTREAM_REJECTED;
        return new Analysis(code, status, providerMessage, message(code, providerMessage, null, null, null));
    }

    /**
     * Checks the model's advertised context window before sending a request.
     * A null result means that no reliable local limit is available.
     */
    public static Optional<Analysis> preflight(JsonNode request, ModelDeployment deployment) {
        if (request == null || deployment == null || deployment.getContextLength() == null
                || deployment.getContextLength() <= 0) return Optional.empty();
        int context = deployment.getContextLength();
        int input = TokenUsageEstimator.estimateInputTokens(request);
        int output = requestedOutputTokens(request);
        Code code = null;
        if (input > context) code = Code.INPUT_TOKEN_LIMIT_EXCEEDED;
        else if (output > context) code = Code.OUTPUT_TOKEN_LIMIT_EXCEEDED;
        else if (output > 0 && ((long) input + output) > context) code = Code.CONTEXT_LENGTH_EXCEEDED;
        if (code == null) return Optional.empty();
        String message = message(code, null, context, input, output);
        return Optional.of(new Analysis(code, 400, null, message));
    }

    public static int requestedOutputTokens(JsonNode request) {
        if (request == null) return 0;
        return Math.max(0, Math.max(request.path("max_completion_tokens").asInt(0),
                request.path("max_tokens").asInt(0)));
    }

    private static Code contextCode(String text) {
        boolean output = containsAny(text, "max_tokens", "max completion", "completion token", "output token", "max_new_tokens", "maximum output");
        boolean input = containsAny(text, "prompt token", "input token", "prompt is too long", "prompt too long", "input is too long", "messages too long");
        if (output && !input) return Code.OUTPUT_TOKEN_LIMIT_EXCEEDED;
        if (input && !output) return Code.INPUT_TOKEN_LIMIT_EXCEEDED;
        return Code.CONTEXT_LENGTH_EXCEEDED;
    }

    private static boolean containsContext(String text) {
        return containsAny(text, "context length", "context_length", "context window", "maximum context",
                "max context", "context size", "prompt is too long", "prompt too long", "input is too long",
                "messages too long", "too many tokens", "token limit", "token_limit", "input limit", "output limit", "completion limit", "max output");
    }

    private static boolean containsFormat(String text) {
        return containsAny(text, "unsupported", "not supported", "does not support", "invalid response_format",
                "response format", "json schema", "json_object", "unknown field", "unrecognized field");
    }

    private static boolean containsCapacity(String text) {
        return containsAny(text, "capacity", "overloaded", "try a different model", "selected model", "temporarily unavailable");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static String extractMessage(JsonNode body) {
        if (body == null || body.isNull()) return "";
        String[] paths = {"error.message", "message", "detail", "error"};
        for (String path : paths) {
            JsonNode value = body;
            for (String part : path.split("\\.")) value = value.path(part);
            if (value.isTextual() && !value.asText().isBlank()) return truncate(value.asText());
        }
        return "";
    }

    private static String message(Code code, String providerMessage, Integer context, Integer input, Integer output) {
        String detail = providerMessage == null || providerMessage.isBlank() ? "" : " Provider: " + providerMessage;
        String limit = context == null ? "" : " (모델 컨텍스트 " + context + " tokens, 예상 입력 " + input + " tokens, 요청 출력 " + output + " tokens)";
        return switch (code) {
            case CONTEXT_LENGTH_EXCEEDED -> "요청의 입력과 출력 예약 토큰 합계가 대상 모델의 컨텍스트 한도를 초과했습니다." + limit + detail;
            case INPUT_TOKEN_LIMIT_EXCEEDED -> "요청 입력 토큰이 대상 모델의 최대 입력 한도를 초과했습니다." + limit + detail;
            case OUTPUT_TOKEN_LIMIT_EXCEEDED -> "요청 출력 토큰 한도가 대상 모델의 최대 출력 한도를 초과했습니다." + limit + detail;
            case REQUEST_FORMAT_UNSUPPORTED -> "대상 모델 또는 Provider가 요청 형식(response_format·토큰 필드 등)을 지원하지 않습니다." + detail;
            case AUTHENTICATION_FAILED -> "Provider 인증 또는 권한이 거부되었습니다." + detail;
            case MODEL_NOT_FOUND -> "Provider에서 요청한 실제 모델 또는 경로를 찾지 못했습니다." + detail;
            case REQUEST_TIMEOUT -> "Provider 요청 시간이 초과되었습니다." + detail;
            case RATE_LIMITED -> "Provider 요청 한도에 도달했습니다." + detail;
            case MODEL_AT_CAPACITY -> "대상 모델이 현재 처리 용량을 초과했습니다." + detail;
            case UPSTREAM_UNAVAILABLE -> "Provider가 일시적으로 사용할 수 없습니다." + detail;
            case UPSTREAM_REJECTED -> "Provider가 요청을 거부했습니다." + detail;
        };
    }

    private static String truncate(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 1000));
    }
}
