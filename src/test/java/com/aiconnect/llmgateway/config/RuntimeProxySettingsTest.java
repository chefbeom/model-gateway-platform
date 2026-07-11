package com.aiconnect.llmgateway.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeProxySettingsTest {
    @Test
    void disablesProxyForBlankConfiguration() {
        assertThat(new RuntimeProxySettings(" ").enabled()).isFalse();
    }

    @Test
    void parsesTailscaleUserspaceHttpProxy() {
        RuntimeProxySettings settings = new RuntimeProxySettings("http://tailscale:1055");
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.address().getHostString()).isEqualTo("tailscale");
        assertThat(settings.address().getPort()).isEqualTo(1055);
    }

    @Test
    void rejectsNonHttpOrCredentialBearingProxyUrls() {
        assertThatThrownBy(() -> new RuntimeProxySettings("socks5://tailscale:1055"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RuntimeProxySettings("http://user:secret@tailscale:1055"))
                .isInstanceOf(IllegalStateException.class);
    }
}
