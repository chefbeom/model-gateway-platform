package com.aiconnect.llmgateway.dataprotection;

import com.aiconnect.llmgateway.domain.DataBoundaryPolicy;
import com.aiconnect.llmgateway.routing.RoutingConstraint;

import java.util.List;

public record DataProtectionDecision(
        EffectiveDataProtectionPolicy policy,
        DataProtectionScanResult scan,
        DataProtectionAction action,
        boolean externalAllowed,
        boolean externalFailoverAllowed,
        boolean externalNoRetentionGpuAllowed,
        boolean externalApiAllowed,
        DataBoundaryPolicy effectiveBoundary,
        List<String> reasonCodes
) {
    public DataProtectionDecision(EffectiveDataProtectionPolicy policy,
                                  DataProtectionScanResult scan,
                                  DataProtectionAction action,
                                  boolean externalAllowed,
                                  boolean externalFailoverAllowed,
                                  List<String> reasonCodes) {
        this(policy, scan, action, externalAllowed, externalFailoverAllowed,
                externalAllowed, externalAllowed,
                externalAllowed ? DataBoundaryPolicy.EXTERNAL_ALLOWED : DataBoundaryPolicy.INTERNAL_ONLY,
                reasonCodes);
    }

    public boolean blocked() { return action == DataProtectionAction.BLOCK; }
    public boolean projectLocalOnly() { return reasonCodes.contains("PROJECT_EXTERNAL_AI_BLOCKED"); }

    public String unavailableCode() {
        return (projectLocalOnly() || effectiveBoundary == DataBoundaryPolicy.INTERNAL_ONLY)
                ? "LOCAL_MODEL_UNAVAILABLE" : "MODEL_UNAVAILABLE";
    }

    public String unavailableMessage() {
        if (projectLocalOnly()) {
            return "이 프로젝트는 외부 AI 전송을 금지합니다. 논리 서비스에 연결된 로컬 Target 중 요청 기능을 지원하는 정상·로드된 모델이 없습니다. 외부 AI로 전환하지 않았습니다.";
        }
        if (effectiveBoundary == DataBoundaryPolicy.INTERNAL_ONLY) {
            return "보안 경계 정책(프로젝트·서비스·API 키)이 사내 GPU 전용 실행만 허용합니다. 사용 가능한 로컬 Target이 없습니다.";
        }
        return "No compatible, healthy or approved deployment is available.";
    }

    public RoutingConstraint routingConstraint() {
        return new RoutingConstraint(externalNoRetentionGpuAllowed, externalApiAllowed, externalFailoverAllowed, reasonCodes);
    }

    public String classificationSummary() {
        return String.join(",", scan.labels());
    }
}
