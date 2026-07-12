package com.aiconnect.llmgateway.usage;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Administrator-only organization usage and infrastructure analytics. */
@RestController
@RequestMapping("/api/admin/organizations")
public class AdminUsageController {
    private final AdminUsageService usage;

    public AdminUsageController(AdminUsageService usage) {
        this.usage = usage;
    }

    @GetMapping("/{organizationId}/usage-overview")
    public AdminUsageService.OrganizationUsageOverview overview(@PathVariable UUID organizationId,
                                                                 @RequestParam(required = false) LocalDate from,
                                                                 @RequestParam(required = false) LocalDate to) {
        return usage.overview(organizationId, from, to);
    }
}
