package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/organizations/{organizationId}")
public class OrganizationRuntimeInventoryController {
    private final InferenceNodeRepository nodes;
    private final RuntimeEndpointRepository endpoints;
    public OrganizationRuntimeInventoryController(InferenceNodeRepository nodes, RuntimeEndpointRepository endpoints) { this.nodes = nodes; this.endpoints = endpoints; }
    @GetMapping("/nodes")
    public List<AdminController.NodeView> nodes(@PathVariable UUID organizationId) {
        return nodes.findByOrganizationId(organizationId).stream().map(AdminController.NodeView::from).toList();
    }
    @GetMapping("/runtime-endpoints")
    public List<AdminController.EndpointView> endpoints(@PathVariable UUID organizationId) {
        return nodes.findByOrganizationId(organizationId).stream().flatMap(node -> endpoints.findByNodeId(node.getId()).stream())
                .map(AdminController.EndpointView::from).toList();
    }
}
