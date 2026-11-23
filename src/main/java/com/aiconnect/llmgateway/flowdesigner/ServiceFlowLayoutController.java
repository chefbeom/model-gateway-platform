package com.aiconnect.llmgateway.flowdesigner;

import com.aiconnect.llmgateway.flowdesigner.ServiceFlowLayoutDtos.FlowLayoutPayload;
import com.aiconnect.llmgateway.flowdesigner.ServiceFlowLayoutDtos.LayoutView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/services/{serviceId}/flow-layout")
public class ServiceFlowLayoutController {
    private final ServiceFlowLayoutService layouts;

    public ServiceFlowLayoutController(ServiceFlowLayoutService layouts) { this.layouts = layouts; }

    @GetMapping
    public LayoutView get(@PathVariable UUID serviceId, HttpServletRequest http) {
        return layouts.get(serviceId, http);
    }

    @PutMapping
    public LayoutView put(@PathVariable UUID serviceId, @RequestBody FlowLayoutPayload body, HttpServletRequest http) {
        return layouts.save(serviceId, body, http);
    }
}
