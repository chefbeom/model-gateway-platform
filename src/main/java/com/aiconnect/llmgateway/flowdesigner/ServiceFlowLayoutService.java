package com.aiconnect.llmgateway.flowdesigner;

import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.flowdesigner.ServiceFlowLayoutDtos.FlowLayoutPayload;
import com.aiconnect.llmgateway.flowdesigner.ServiceFlowLayoutDtos.LayoutView;
import com.aiconnect.llmgateway.flowdesigner.ServiceFlowLayoutDtos.NodePositionDto;
import com.aiconnect.llmgateway.flowdesigner.ServiceFlowLayoutDtos.ViewportDto;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ServiceFlowLayoutService {
    static final int MAX_JSON_CHARS = 60_000;
    static final int MAX_NODES = 500;
    private static final double MAX_COORD = 1_000_000d;
    private static final Pattern NODE_KEY = Pattern.compile(
            "^(service|guardrail|prompt|cache|router|masker|(project|target|deployment|endpoint|provider):[A-Za-z0-9-]{1,64})$");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ServiceFlowLayoutRepository layouts;
    private final LlmServiceRepository services;
    @Autowired(required = false)
    private TeamAccessService access;

    public ServiceFlowLayoutService(ServiceFlowLayoutRepository layouts, LlmServiceRepository services) {
        this.layouts = layouts;
        this.services = services;
    }

    @Transactional(readOnly = true)
    public LayoutView get(UUID serviceId, HttpServletRequest http) {
        LlmService service = requireEditableService(serviceId, http);
        ServiceFlowLayout stored = layouts.findById(serviceId).orElse(null);
        if (stored == null || !service.getOrganizationId().equals(stored.getOrganizationId())) {
            return new LayoutView(serviceId, 0, null, null);
        }
        try {
            return new LayoutView(serviceId, stored.getRevision(), stored.getUpdatedAt(),
                    MAPPER.readValue(stored.getLayoutJson(), FlowLayoutPayload.class));
        } catch (Exception e) {
            return new LayoutView(serviceId, 0, null, null);
        }
    }

    @Transactional
    public LayoutView save(UUID serviceId, FlowLayoutPayload payload, HttpServletRequest http) {
        LlmService service = requireEditableService(serviceId, http);
        FlowLayoutPayload normalized = validateAndNormalize(payload);
        String json;
        try { json = MAPPER.writeValueAsString(normalized); }
        catch (Exception e) { throw invalid("Layout could not be serialized."); }
        if (json.length() > MAX_JSON_CHARS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FLOW_LAYOUT_TOO_LARGE", "Layout exceeds the maximum size.");
        }
        UUID actor = CurrentActor.userIdOrNull();
        ServiceFlowLayout row = layouts.findById(serviceId).orElse(null);
        if (row == null) {
            row = new ServiceFlowLayout(serviceId, service.getOrganizationId(), json, actor);
        } else {
            row.replace(json, actor);
        }
        row = layouts.save(row);
        return new LayoutView(serviceId, row.getRevision(), row.getUpdatedAt(), normalized);
    }

    public static FlowLayoutPayload validateAndNormalize(FlowLayoutPayload p) {
        if (p == null) throw invalid("Layout body is required.");
        if (p.version() == null || p.version() != 1) throw invalid("Unsupported layout version.");
        ViewportDto v = p.viewport();
        if (v == null || p.nodes() == null) throw invalid("viewport and nodes are required.");
        if (!coord(v.x()) || !coord(v.y())) throw invalid("Viewport position is out of range.");
        if (v.zoom() == null || !Double.isFinite(v.zoom()) || v.zoom() < 0.1 || v.zoom() > 4) {
            throw invalid("Viewport zoom is out of range.");
        }
        if (p.nodes().size() > MAX_NODES) throw invalid("Too many nodes.");
        Map<String, NodePositionDto> sorted = new TreeMap<>();
        for (Map.Entry<String, NodePositionDto> e : p.nodes().entrySet()) {
            String key = e.getKey();
            NodePositionDto n = e.getValue();
            if (key == null || !NODE_KEY.matcher(key).matches()) throw invalid("Invalid node id.");
            if (n == null || !coord(n.x()) || !coord(n.y())) throw invalid("Node position is out of range.");
            sorted.put(key, new NodePositionDto(n.x(), n.y(), Boolean.TRUE.equals(n.collapsed()) ? Boolean.TRUE : null));
        }
        return new FlowLayoutPayload(1, new ViewportDto(v.x(), v.y(), v.zoom()), sorted);
    }

    private static boolean coord(Double d) {
        return d != null && Double.isFinite(d) && Math.abs(d) <= MAX_COORD;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "FLOW_LAYOUT_INVALID", message);
    }

    private LlmService requireEditableService(UUID serviceId, HttpServletRequest http) {
        LlmService service = services.findById(serviceId).filter(s -> !s.isDeleted())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "Service not found."));
        if (!isOrganizationOrPlatformAdmin(http, service.getOrganizationId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_ADMIN_REQUIRED",
                    "Only an organization administrator may use the flow layout.");
        }
        return service;
    }

    private boolean isOrganizationOrPlatformAdmin(HttpServletRequest http, UUID organizationId) {
        if (http != null && Boolean.TRUE.equals(http.getAttribute("aiconnect.platform-admin"))) return true;
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        return actor != null && (actor.platformAdmin() || (access != null && access.isOrganizationAdmin(actor, organizationId)));
    }
}
