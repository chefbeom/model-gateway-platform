package com.aiconnect.llmgateway.diagnostic;

import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.gateway.RequestCapabilityDetector;
import com.aiconnect.llmgateway.routing.RoutingDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Stores and reads safe, deterministic explanations for failed requests. */
@Service
public class RequestDiagnosticService {
    private final RequestDiagnosticRepository diagnostics;
    private final ObjectMapper objectMapper;

    public RequestDiagnosticService(RequestDiagnosticRepository diagnostics, ObjectMapper objectMapper) {
        this.diagnostics = diagnostics;
        this.objectMapper = objectMapper;
    }

    public void recordFailure(UUID requestId, ObjectNode request, LlmService service,
                              RoutingDecision decision, String finalCode, int httpStatus,
                              int attemptedCount) {
        try {
            RequestDiagnosticPayload payload = build(request, service, decision, finalCode, httpStatus, attemptedCount);
            diagnostics.save(new RequestDiagnostic(requestId, objectMapper.writeValueAsString(payload)));
        } catch (Exception exception) {
            // A diagnostic must never turn a completed gateway failure into a second failure.
        }
    }

    @Transactional(readOnly = true)
    public Optional<RequestDiagnosticPayload> view(UUID requestId) {
        return diagnostics.findById(requestId).flatMap(item -> {
            try { return Optional.of(objectMapper.readValue(item.getPayloadJson(), RequestDiagnosticPayload.class)); }
            catch (Exception ignored) { return Optional.empty(); }
        });
    }

    private RequestDiagnosticPayload build(ObjectNode request, LlmService service, RoutingDecision decision,
                                           String finalCode, int httpStatus, int attemptedCount) {
        String requestType = RequestCapabilityDetector.requestType(request);
        JsonNode messages = request.path("messages");
        JsonNode tools = request.path("tools");
        JsonNode responseFormat = request.path("response_format");
        RequestDiagnosticPayload.RequestProfile profile = new RequestDiagnosticPayload.RequestProfile(
                request.path("model").asText(null), requestType,
                sorted(RequestCapabilityDetector.detect(request)), request.path("stream").asBoolean(false),
                messages.isArray() ? messages.size() : 0, tools.isArray() ? tools.size() : 0,
                responseFormat.isObject(), responseFormat.path("type").asText(null),
                request.has("max_tokens"), request.has("max_completion_tokens"));
        RequestDiagnosticPayload.ServicePolicy policy = new RequestDiagnosticPayload.ServicePolicy(
                decision.failoverPolicy(), decision.retryPolicy(), decision.degradedAllowed(),
                List.copyOf(decision.requiredCapabilities()));

        List<RequestDiagnosticPayload.Target> targetViews = decision.evaluations().stream().map(item ->
                new RequestDiagnosticPayload.Target(item.targetId(), item.deploymentId(), item.displayName(), item.providerType(),
                        item.priority(), item.weight(), item.targetEnabled(), item.degraded(), item.deploymentEnabled(),
                        item.loaded(), item.deploymentHealth(), item.endpointDisplayName(), item.providerDisplayName(),
                        item.activeRequests(), item.maxConcurrency(), item.requiredCapabilities(), item.availableCapabilities(),
                        item.missingCapabilities(), item.eligible(), item.reasonCodes())).toList();
        List<RequestDiagnosticPayload.Recommendation> recommendations = recommendations(finalCode, targetViews, httpStatus);
        return new RequestDiagnosticPayload(1, profile, policy, finalCode, httpStatus, attemptedCount,
                summary(finalCode, targetViews), targetViews, recommendations);
    }

    private List<RequestDiagnosticPayload.Recommendation> recommendations(String finalCode,
                                                                           List<RequestDiagnosticPayload.Target> targets,
                                                                           int httpStatus) {
        Map<String, RequestDiagnosticPayload.Recommendation> result = new LinkedHashMap<>();
        for (RequestDiagnosticPayload.Target target : targets) {
            for (String reason : target.reasonCodes()) addRecommendation(result, reason, target, null);
        }
        addRecommendation(result, finalCode, null, httpStatus);
        return new ArrayList<>(result.values());
    }

    private void addRecommendation(Map<String, RequestDiagnosticPayload.Recommendation> result,
                                   String code, RequestDiagnosticPayload.Target target, Integer httpStatus) {
        if (code == null || result.containsKey(code)) return;
        String missing = target == null || target.missingCapabilities().isEmpty()
                ? "필요한 기능과 대상 모델의 등록 상태를 확인하세요."
                : String.join(", ", target.missingCapabilities());
        RequestDiagnosticPayload.Recommendation recommendation = switch (code) {
            case "CAPABILITY_MISSING" -> new RequestDiagnosticPayload.Recommendation(code, "모델 기능 등록 확인",
                    "요청에 필요한 기능(" + missing + ")이 대상 모델에 등록되어 있지 않습니다. 외부 AI 또는 배포 모델 설정에서 실제 지원 여부를 확인한 뒤 Capability를 등록하세요.");
            case "ENDPOINT_UNHEALTHY", "ENDPOINT_MISSING", "ENDPOINT_DISABLED" -> new RequestDiagnosticPayload.Recommendation(code, "Runtime 연결 확인",
                    "LM Studio Endpoint의 실행 상태, 주소, 방화벽과 포트 연결을 확인한 뒤 인프라 연결 확인을 다시 실행하세요.");
            case "DEPLOYMENT_UNHEALTHY", "DEPLOYMENT_NOT_LOADED" -> new RequestDiagnosticPayload.Recommendation(code, "모델 배포 상태 확인",
                    "대상 모델이 로드되어 있고 정상 상태인지 확인하세요. 모델을 다시 로드하거나 정상 Deployment를 서비스 Target에 연결하세요.");
            case "EXTERNAL_AUTO_FAILOVER_NOT_ALLOWED" -> new RequestDiagnosticPayload.Recommendation(code, "외부 AI Failover 권한 확인",
                    "프로젝트 외부 AI 권한에서 자동 Failover를 승인하고 만료일·월별 비용 한도를 확인하세요.");
            case "EXTERNAL_MANUAL_ACCESS_NOT_ALLOWED" -> new RequestDiagnosticPayload.Recommendation(code, "외부 AI 사용 권한 확인",
                    "프로젝트가 해당 외부 Provider를 수동으로 사용할 수 있도록 관리자 승인을 받으세요.");
            case "EXTERNAL_PROJECT_REQUIRED" -> new RequestDiagnosticPayload.Recommendation(code, "프로젝트 연결 확인",
                    "외부 Provider 요청에는 프로젝트 컨텍스트가 필요합니다. API 키가 속한 프로젝트와 외부 AI 권한을 확인하세요.");
            case "EXTERNAL_ACCESS_UNAVAILABLE" -> new RequestDiagnosticPayload.Recommendation(code, "외부 권한 서비스 확인",
                    "외부 AI 권한 정보를 조회할 수 없습니다. Gateway의 외부 Provider 설정과 데이터베이스 연결 상태를 확인하세요.");
            case "EXTERNAL_PROVIDER_UNHEALTHY", "EXTERNAL_PROVIDER_DISABLED", "EXTERNAL_PROVIDER_MISSING" -> new RequestDiagnosticPayload.Recommendation(code, "외부 Provider 설정 확인",
                    "외부 Provider의 활성화 상태, API 연결 확인 결과와 등록된 모델 상태를 확인하세요.");
            case "COMPATIBILITY_MISMATCH" -> new RequestDiagnosticPayload.Recommendation(code, "호환성 Key 확인",
                    "STRICT 정책에서는 기준 Deployment와 같은 compatibility key를 사용해야 합니다. 서비스 정책 또는 Deployment 설정을 확인하세요.");
            case "CONCURRENCY_LIMIT_REACHED" -> new RequestDiagnosticPayload.Recommendation(code, "동시 요청 한도 확인",
                    "모델의 동시 요청이 한도에 도달했습니다. 진행 중인 요청을 기다리거나 동시성 한도를 조정하세요.");
            case "TARGET_DISABLED", "DEGRADED_NOT_ALLOWED" -> new RequestDiagnosticPayload.Recommendation(code, "서비스 Target 활성화 확인",
                    "서비스 Target이 비활성화 또는 Degraded 제외 상태입니다. 서비스 라우팅 정책과 Target 상태를 확인하세요.");
            case "UPSTREAM_REJECTED" -> upstreamRecommendation(code, httpStatus);
            case "RUNTIME_UNAVAILABLE", "STREAM_START_FAILED" -> new RequestDiagnosticPayload.Recommendation(code, "Runtime 응답 확인",
                    "Runtime이 응답을 시작하지 못했습니다. Endpoint 상태와 연결 제한 시간을 확인한 뒤 재시도하세요.");
            case "MODEL_AT_CAPACITY" -> new RequestDiagnosticPayload.Recommendation(code, "모델 용량 확인",
                    "모델이 처리 한도에 도달했습니다. 잠시 후 재시도하거나 다른 정상 Target을 추가하세요.");
            case "MODEL_UNAVAILABLE" -> new RequestDiagnosticPayload.Recommendation(code, "라우팅 조건 확인",
                    "아래 Target별 제외 사유를 확인하고 적어도 하나의 정상·호환·승인된 Target을 준비하세요.");
            case "DATA_POLICY_BLOCKED" -> new RequestDiagnosticPayload.Recommendation(code, "데이터 보호 정책 확인",
                    "요청에 민감정보가 감지되어 외부 전송이 차단되었습니다. 프로젝트·API 키·서비스의 보호 모드와 허용 동작을 확인하거나 민감정보를 제거하세요.");
            case "DATA_PROTECTION_EXTERNAL_BLOCKED" -> new RequestDiagnosticPayload.Recommendation(code, "외부 전송 차단 정책 확인",
                    "보호 정책이 외부 Provider 전송을 허용하지 않습니다. 로컬 Target을 준비하거나 정책 범위를 프로젝트·키별로 조정하세요.");
            case "DATA_PROTECTION_EXTERNAL_FAILOVER_BLOCKED" -> new RequestDiagnosticPayload.Recommendation(code, "외부 Failover 차단 정책 확인",
                    "보호 정책이 자동 외부 Failover를 허용하지 않습니다. 로컬 장애 시 요청을 차단할지, 승인된 외부 Failover를 허용할지 선택하세요.");
            default -> null;
        };
        if (recommendation != null) result.put(code, recommendation);
    }

    private RequestDiagnosticPayload.Recommendation upstreamRecommendation(String code, Integer httpStatus) {
        if (httpStatus != null && (httpStatus == 401 || httpStatus == 403)) {
            return new RequestDiagnosticPayload.Recommendation(code, "Provider API 키·권한 확인",
                    "Provider가 인증 또는 권한을 거부했습니다. 외부 Provider API 키의 유효성·권한·만료 상태와 등록된 Base URL을 확인하세요.");
        }
        if (httpStatus != null && httpStatus == 404) {
            return new RequestDiagnosticPayload.Recommendation(code, "Provider 모델·경로 확인",
                    "Provider가 모델 또는 경로를 찾지 못했습니다. 등록된 실제 Provider 모델 ID와 Chat Completions 경로를 확인하세요.");
        }
        if (httpStatus != null && (httpStatus == 429 || httpStatus == 503)) {
            return new RequestDiagnosticPayload.Recommendation(code, "Provider 처리 한도 확인",
                    "Provider가 처리 한도 또는 일시적인 과부하로 요청을 거부했습니다. Provider 사용량·rate limit을 확인하고 Retry/Failover 정책을 조정하세요.");
        }
        return new RequestDiagnosticPayload.Recommendation(code, "Provider 요청 형식 확인",
                "Provider가 요청을 거부했습니다. 모델이 요청 기능을 지원하는지, 토큰 제한 필드와 응답 형식이 Provider 규격에 맞는지 확인하세요.");
    }

    private String summary(String code, List<RequestDiagnosticPayload.Target> targets) {
        long eligible = targets.stream().filter(RequestDiagnosticPayload.Target::eligible).count();
        long excluded = targets.size() - eligible;
        if ("MODEL_UNAVAILABLE".equals(code)) return "호환되고 정상이며 승인된 Target이 없습니다. 평가 대상 " + targets.size() + "개 중 " + excluded + "개가 제외되었습니다.";
        if ("UPSTREAM_REJECTED".equals(code)) return "선택된 Provider가 요청을 거부했고 현재 Retry 정책에서 Failover가 허용되지 않았습니다.";
        if ("DATA_POLICY_BLOCKED".equals(code)) return "민감정보 보호 정책이 요청의 외부 전송을 차단했습니다. 원문은 저장하지 않고 분류명만 기록했습니다.";
        if ("DATA_PROTECTION_EXTERNAL_BLOCKED".equals(code)) return "데이터 보호 정책에 따라 외부 Target이 제외되었습니다. 로컬 Target만 선택할 수 있습니다.";
        if ("DATA_PROTECTION_EXTERNAL_FAILOVER_BLOCKED".equals(code)) return "데이터 보호 정책에 따라 자동 외부 Failover가 제외되었습니다.";
        return "요청 처리 중 " + code + " 오류가 발생했습니다. 평가 대상 " + targets.size() + "개, 사용 가능 " + eligible + "개입니다.";
    }

    private List<String> sorted(Set<String> values) {
        return List.copyOf(new TreeSet<>(values));
    }
}
