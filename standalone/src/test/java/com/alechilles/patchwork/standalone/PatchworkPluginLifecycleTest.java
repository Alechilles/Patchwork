package com.alechilles.patchwork.standalone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alechilles.patchwork.embedded.StandalonePatchworkService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PatchworkPluginLifecycleTest {
    @Test
    void lifecycleCreatesStartsAndClosesOneExactStandaloneService() throws Exception {
        AtomicInteger created = new AtomicInteger();
        RecordingService service = new RecordingService();
        StandalonePluginLifecycle lifecycle = new StandalonePluginLifecycle();
        lifecycle.setup(() -> {
            created.incrementAndGet();
            return service;
        });

        lifecycle.setup(() -> { throw new AssertionError("setup must not create a second service"); });
        lifecycle.start();
        lifecycle.shutdown();

        assertEquals(1, created.get(), "setup must create one provider service");
        assertEquals(1, service.starts.get(), "start must delegate once to the created service");
        assertEquals(1, service.closes.get(), "shutdown must close the exact created service");
        assertNull(lifecycle.service(), "successful shutdown must release the closed service");
    }

    @Test
    void failedShutdownRetainsTheServiceForAnExactRetry() throws Exception {
        RecordingService service = new RecordingService();
        service.failFirstClose = true;
        StandalonePluginLifecycle lifecycle = new StandalonePluginLifecycle();

        lifecycle.setup(() -> service);
        assertThrows(IllegalStateException.class, lifecycle::shutdown);
        assertSame(service, lifecycle.service(), "failed shutdown must retain the exact retry handle");
        lifecycle.shutdown();

        assertEquals(2, service.closes.get());
        assertNull(lifecycle.service());
    }

    private static final class RecordingService implements StandalonePatchworkService {
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private boolean failFirstClose;

        @Override public void start() { starts.incrementAndGet(); }
        @Override public void close() {
            if (closes.incrementAndGet() == 1 && failFirstClose) throw new IllegalStateException("transient close failure");
        }
    }
}
