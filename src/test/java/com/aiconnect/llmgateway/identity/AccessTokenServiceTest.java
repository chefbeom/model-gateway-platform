package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokenServiceTest {
    @Test
    void issuesAndVerifiesSignedPlatformToken() {
        AccessTokenService service = new AccessTokenService(new ObjectMapper(), new AuthProperties("signing-key", "refresh", 900, 3600));
        AppUser user = new AppUser("admin@example.com", "hash", true);
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", id);

        AuthPrincipal principal = service.parse(service.issue(user));

        assertThat(principal.userId()).isEqualTo(id);
        assertThat(principal.email()).isEqualTo("admin@example.com");
        assertThat(principal.platformAdmin()).isTrue();
    }

    @Test
    void rejectsTokenWhoseSignatureWasChanged() {
        AccessTokenService service = new AccessTokenService(new ObjectMapper(), new AuthProperties("signing-key", "refresh", 900, 3600));
        AppUser user = new AppUser("admin@example.com", "hash", false);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        String token = service.issue(user);
        int signatureStart = token.lastIndexOf('.') + 1;
        char original = token.charAt(signatureStart);
        String tampered = token.substring(0, signatureStart) + (original == 'a' ? 'b' : 'a') + token.substring(signatureStart + 1);

        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(ApiException.class);
    }
}
