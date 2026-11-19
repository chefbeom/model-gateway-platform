package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.ApiKeyCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SpendQuotaFilterBodyLimitTest {
    @Test void authenticatedOversizeIsRejectedBeforeQuotaReservation() throws Exception {
        var keys = mock(ApiKeyService.class);
        var quota = mock(SpendQuotaService.class);
        var credentials = mock(ApiKeyCredentials.class);
        when(keys.authenticate("Bearer valid")).thenReturn(credentials);
        var request = new MockHttpServletRequest("POST", "/v1/chat/completions") {
            @Override public long getContentLengthLong() { return 10L * 1024 * 1024 + 1; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                throw new AssertionError("An oversized declared body must not be read");
            }
        };
        request.addHeader("Authorization", "Bearer valid");
        var response = new MockHttpServletResponse();
        new SpendQuotaFilter(new ObjectMapper(), quota, keys).doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(413);
        verifyNoInteractions(quota);
    }
}
