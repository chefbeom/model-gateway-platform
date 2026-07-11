package com.aiconnect.llmgateway.usage;

import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Login-session usage API. The server derives the visible scope from the current actor. */
@RestController
@RequestMapping("/api/portal/organizations/{organizationId}/usage-overview")
public class PortalUsageController {
    private final AdminUsageService usage;

    public PortalUsageController(AdminUsageService usage) {
        this.usage = usage;
    }

    @GetMapping
    public AdminUsageService.OrganizationUsageOverview overview(@PathVariable UUID organizationId,
                                                                 @RequestParam(required = false) LocalDate from,
                                                                 @RequestParam(required = false) LocalDate to,
                                                                 @RequestParam(required = false) UUID projectId) {
        AuthPrincipal actor = CurrentActor.principal().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                "PORTAL_AUTH_REQUIRED", "An authenticated user session is required."));
        return usage.overviewForActor(organizationId, actor, from, to, projectId);
    }
}
