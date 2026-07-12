package com.aiconnect.llmgateway.cluster;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalClusterTaskCoordinatorTest {
    @Test
    void standaloneAlwaysRunsTask() {
        AtomicInteger calls = new AtomicInteger();
        boolean ran = new LocalClusterTaskCoordinator().runIfLeader("test", calls::incrementAndGet);
        assertTrue(ran);
        assertEquals(1, calls.get());
    }
}
