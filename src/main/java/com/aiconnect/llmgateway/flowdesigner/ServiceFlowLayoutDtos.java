package com.aiconnect.llmgateway.flowdesigner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Wire types for the flow-designer layout API. Unknown fields are dropped on deserialization. */
public final class ServiceFlowLayoutDtos {
    private ServiceFlowLayoutDtos() { }

    @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FlowLayoutPayload(Integer version, ViewportDto viewport, Map<String, NodePositionDto> nodes) { }

    @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ViewportDto(Double x, Double y, Double zoom) { }

    @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodePositionDto(Double x, Double y, Boolean collapsed) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LayoutView(UUID serviceId, long revision, Instant updatedAt, FlowLayoutPayload layout) { }
}
