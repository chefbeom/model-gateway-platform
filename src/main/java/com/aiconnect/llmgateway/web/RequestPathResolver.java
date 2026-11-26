package com.aiconnect.llmgateway.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * Resolves the canonical, URL-decoded application path for security, RBAC, quota, and retention
 * filters so percent-encoded route segments cannot bypass filter predicates while still matching
 * Spring MVC handlers.
 */
public final class RequestPathResolver {
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    static {
        PATH_HELPER.setRemoveSemicolonContent(true);
        PATH_HELPER.setUrlDecode(true);
    }

    private RequestPathResolver() { }

    public static String resolve(HttpServletRequest request) {
        if (request == null) return "/";
        String path = PATH_HELPER.getPathWithinApplication(request);
        if (path == null || path.isEmpty()) return "/";
        String collapsed = path.replaceAll("/{2,}", "/");
        if (!collapsed.startsWith("/")) {
            collapsed = "/" + collapsed;
        }
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
    }
}
