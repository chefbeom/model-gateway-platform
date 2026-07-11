package com.aiconnect.llmgateway.monitoring;

import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/organizations/{organizationId}/requests")
public class AdminRequestExplorerController {
    private final AdminRequestExplorerService explorer;
    public AdminRequestExplorerController(AdminRequestExplorerService explorer) { this.explorer = explorer; }
    @GetMapping
    public AdminRequestExplorerService.PageResult search(@PathVariable UUID organizationId,
                                                         @RequestParam(required = false) UUID projectId,
                                                         @RequestParam(required = false) UUID serviceId,
                                                         @RequestParam(required = false) UUID deploymentId,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "false") boolean failoverOnly,
                                                         @RequestParam(required = false) Instant from,
                                                         @RequestParam(required = false) Instant to,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "25") int size) {
        return explorer.search(organizationId, projectId, serviceId, deploymentId, status, failoverOnly, from, to, page, size);
    }
}
