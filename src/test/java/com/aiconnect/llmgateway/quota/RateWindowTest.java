package com.aiconnect.llmgateway.quota;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class RateWindowTest {
    @Test
    void rejectsRequestsOverTheRollingMinuteLimitAndExpiresOldEntries() {
        RateWindow window = new RateWindow();
        Instant started = Instant.parse("2026-07-11T00:00:00Z");

        assertThat(window.tryAcquire(2, started)).isTrue();
        assertThat(window.tryAcquire(2, started.plusSeconds(1))).isTrue();
        assertThat(window.tryAcquire(2, started.plusSeconds(2))).isFalse();
        assertThat(window.tryAcquire(2, started.plusSeconds(61))).isTrue();
    }
}
