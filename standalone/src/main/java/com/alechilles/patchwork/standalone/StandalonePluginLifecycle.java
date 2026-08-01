package com.alechilles.patchwork.standalone;

import com.alechilles.patchwork.embedded.StandalonePatchworkService;
import java.util.Objects;
import java.util.function.Supplier;

/** Retains the standalone provider through setup, start, and retryable shutdown. */
final class StandalonePluginLifecycle {
    private StandalonePatchworkService service;

    synchronized void setup(Supplier<StandalonePatchworkService> bootstrap) {
        if (service == null) service = Objects.requireNonNull(bootstrap.get(), "standalone bootstrap returned null");
    }

    synchronized void start() {
        if (service == null) throw new IllegalStateException("Patchwork standalone provider was not initialized.");
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
