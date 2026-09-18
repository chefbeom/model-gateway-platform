package com.aiconnect.llmgateway.runtime;

/** Normalizes runtime URLs while accepting either a host root or an OpenAI /v1 base. */
public final class RuntimeUrl {
    private RuntimeUrl() { }

    public static String append(String baseUrl, String path) {
        String base = trimTrailingSlash(baseUrl);
        String suffix = path.startsWith("/") ? path : "/" + path;
        return base + suffix;
    }

    public static String openAi(String baseUrl, String resource) {
        String base = trimTrailingSlash(baseUrl);
        String suffix = resource.startsWith("/") ? resource : "/" + resource;
        return base.endsWith("/v1") ? base + suffix : base + "/v1" + suffix;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "";
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }
}