package com.alechilles.patchwork.standalone;

import com.alechilles.patchwork.embedded.StandalonePatchworkService;
import java.util.Objects;
import java.util.function.Supplier;

/** Starts and retains the standalone provider during setup for retryable shutdown. */
final class StandalonePluginLifecycle {
    private StandalonePatchworkService service;

    synchronized void setup(Supplier<StandalonePatchworkService> bootstrap) {
        if (service != null) return;
        service = Objects.requireNonNull(bootstrap.get(), "standalone bootstrap returned null");
        service.start();
    }

    synchronized void shutdown() {
        StandalonePatchworkService closing = service;
        if (closing == null) return;
        closing.close();
        if (service == closing) service = null;
    }

    synchronized StandalonePatchworkService service() { return service; }
}
