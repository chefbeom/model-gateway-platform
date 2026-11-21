package com.aiconnect.llmgateway.portal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdvertisedGatewayUrlTest {
    private String url(String scope, String value) {
        return DeveloperPortalController.endpoint(scope, scope, value).url();
    }

    @Test void optionalUrlsAndVersionedPrefixes() {
        assertNull(url("INTERNAL", ""));
        assertNull(url("EXTERNAL", "  "));
        assertEquals("http://internal.example/prefix/v1", url("INTERNAL", "http://internal.example/prefix/"));
        assertEquals("https://api.example/prefix/v1", url("EXTERNAL", "https://api.example/prefix/v1/"));
    }

    @Test void invalidOrUnsafeUrlsAreNotAdvertised() {
        for (String value : new String[]{"http://api.example/v1", "ftp://api.example/v1", "https://user:pass@api.example/v1", "https://api.example/v1?key=secret", "https://api.example/v1#fragment", "https://api.example/a/../v1", "https://api.example/%2e%2e/v1", "https://api.example:65536/v1", "https://", "//api.example"}) {
            assertNull(url("EXTERNAL", value), value);
        }
    }
}
