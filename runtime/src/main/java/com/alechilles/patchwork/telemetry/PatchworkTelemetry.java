package com.alechilles.patchwork.telemetry;

import com.alechilles.beacon.embedded.EmbeddedTelemetryBootstrap;
import com.alechilles.beacon.embedded.EmbeddedTelemetryService;
import com.alechilles.beacon.embedded.TelemetryProjectContribution;
import com.alechilles.patchwork.PatchworkVersion;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Patchwork's independent, hosted-only telemetry project.
 *
 * <p>The wrapper deliberately keeps telemetry optional: loading, starting, and closing
 * telemetry are isolated from Patchwork's generation and reload lifecycle.</p>
 */
public final class PatchworkTelemetry implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(PatchworkTelemetry.class.getName());
    private static final String DESCRIPTOR = "META-INF/beacon/projects/patchwork.json";
    private static final int MAX_WARNINGS = 2;

    private final EmbeddedTelemetryService service;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean warned = new AtomicBoolean();
    private int warnings;

    private PatchworkTelemetry(EmbeddedTelemetryService service) {
        this.service = service;
    }

    /** Builds the contribution without emitting telemetry or registering a writable token. */
    public static PatchworkTelemetry prepare(JavaPlugin hostPlugin) {
        if (hostPlugin == null) {
            return new PatchworkTelemetry(null);
        }
        PatchworkTelemetry fallback = new PatchworkTelemetry(null);
        try {
            TelemetryProjectContribution contribution = TelemetryProjectContribution.builder()
                    .descriptorResource(PatchworkTelemetry.class, DESCRIPTOR)
                    .logicalPluginIdentifier("Alechilles:Patchwork")
                    .logicalPluginVersion(PatchworkVersion.current())
                    .build();
            return new PatchworkTelemetry(EmbeddedTelemetryBootstrap.contribute(hostPlugin, contribution));
        } catch (RuntimeException | LinkageError failure) {
            fallback.warn("Unable to prepare Patchwork telemetry; continuing without it.", failure);
            return fallback;
        }
    }

    /** Creates a no-op wrapper for tests and non-Hytale composition paths. */
    public static PatchworkTelemetry disabled() {
        return new PatchworkTelemetry(null);
    }

    public boolean enabled() {
        return service != null && service.disabledReason() == null;
    }

    public void start() {
        if (closed.get() || service == null || !started.compareAndSet(false, true)) {
            return;
        }
        try {
            service.start();
        } catch (RuntimeException | LinkageError failure) {
            warn("Patchwork telemetry failed to start; continuing without it.", failure);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || service == null) {
            return;
        }
        try {
            service.shutdown();
        } catch (RuntimeException | LinkageError failure) {
            warn("Patchwork telemetry failed to close cleanly; continuing shutdown.", failure);
        }
    }

    /** Retained for binary compatibility; Patchwork no longer emits lifecycle events. */
    @Deprecated(forRemoval = true)
    public void recordLifecycle(String eventName, int durationMs, boolean success, String detail) {
    }

    /** Retained for binary compatibility; Patchwork no longer emits error events. */
    @Deprecated(forRemoval = true)
    public void recordError(String eventName, Throwable failure, String detail) {
    }

    /** Retained for binary compatibility; Patchwork no longer emits performance events. */
    @Deprecated(forRemoval = true)
    public void recordPerformance(String eventName, int durationMs, String detail) {
    }

    /** Retained for binary compatibility; Patchwork no longer emits usage events. */
    @Deprecated(forRemoval = true)
    public void recordUsage(String eventName, String detail) {
    }

    /** Retained for binary compatibility; Patchwork no longer emits breadcrumbs. */
    @Deprecated(forRemoval = true)
    public void breadcrumb(String category, String detail) {
    }

    private synchronized void warn(String message, Throwable failure) {
        if (warnings >= MAX_WARNINGS) return;
        warnings++;
        if (warned.compareAndSet(false, true)) {
            LOG.log(Level.WARNING, message, failure);
        } else {
            LOG.log(Level.WARNING, message + " (additional failure)");
        }
    }
}
