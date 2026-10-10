package com.aiconnect.llmgateway.dataprotection;

import com.aiconnect.llmgateway.routing.RoutingConstraint;

import java.util.List;

public record DataProtectionDecision(
        EffectiveDataProtectionPolicy policy,
        DataProtectionScanResult scan,
        DataProtectionAction action,
        boolean externalAllowed,
        boolean externalFailoverAllowed,
        List<String> reasonCodes
) {
    public boolean blocked() { return action == DataProtectionAction.BLOCK; }
    public boolean projectLocalOnly() { return reasonCodes.contains("PROJECT_EXTERNAL_AI_BLOCKED"); }

    public String unavailableCode() { return projectLocalOnly() ? "LOCAL_MODEL_UNAVAILABLE" : "MODEL_UNAVAILABLE"; }

    public String unavailableMessage() {
        return projectLocalOnly()
                ? "이 프로젝트는 외부 AI 전송을 금지합니다. 논리 서비스에 연결된 로컬 Target 중 요청 기능을 지원하는 정상·로드된 모델이 없습니다. 외부 AI로 전환하지 않았습니다."
                : "No compatible, healthy or approved deployment is available.";
    }

    public RoutingConstraint routingConstraint() {
        return new RoutingConstraint(externalAllowed, externalFailoverAllowed, reasonCodes);
    }

    public String classificationSummary() {
        return String.join(",", scan.labels());
    }
}
