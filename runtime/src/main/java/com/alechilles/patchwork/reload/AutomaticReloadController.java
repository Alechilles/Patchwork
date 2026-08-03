package com.alechilles.patchwork.reload;

import com.alechilles.patchwork.generation.GenerationDependencyIndex;
import com.alechilles.patchwork.discovery.PatchScanner;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Debounces relevant source changes into serialized, elected-owner reloads.
 *
 * <p>The controller intentionally owns no file watcher. Hytale (or another
 * host) supplies normalized events, while this class only decides whether the
 * current dependency snapshot needs another generation pass.</p>
 */
public final class AutomaticReloadController implements AutoCloseable {
    public static final Duration DEBOUNCE = Duration.ofSeconds(1);

    @FunctionalInterface
    public interface ReloadAction {
        PatchReloadCoordinator.ReloadOutcome reload(long expectedOwnershipEpoch);
    }

    @FunctionalInterface
    public interface Cancellable {
        void cancel();
    }

    /** Minimal scheduler seam for deterministic controller tests. */
    public interface Scheduler extends AutoCloseable {
        Cancellable schedule(Duration delay, Runnable action);
        @Override default void close() { }
    }

    private final Object gate = new Object();
    private final ReloadAction reloadAction;
    private final Scheduler scheduler;
    private long ownershipEpoch = Long.MIN_VALUE;
    private GenerationDependencyIndex dependencies = GenerationDependencyIndex.empty();
    private boolean active;
    private boolean running;
    private boolean dirty;
    private Cancellable scheduled;

    public AutomaticReloadController(ReloadAction reloadAction) {
        this(reloadAction, daemonScheduler());
    }

    public AutomaticReloadController(ReloadAction reloadAction, Scheduler scheduler) {
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Activates the controller for one elected ownership epoch. */
    public void activate(long epoch, GenerationDependencyIndex dependencies) {
        synchronized (gate) {
            if (epoch < ownershipEpoch) return;
            cancelScheduledLocked();
            ownershipEpoch = epoch;
            this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
            active = true;
            running = false;
            dirty = false;
        }
    }

    /** Replaces the dependency metadata used by subsequent source events. */
    public void updateDependencies(long epoch, GenerationDependencyIndex dependencies) {
        synchronized (gate) {
            if (!active || epoch != ownershipEpoch) return;
            this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        }
    }

    /** Accepts a host-normalized source event when it can affect the current plan. */
    public void onSourceEvent(long epoch, PatchworkSourceEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (gate) {
            if (!active || epoch != ownershipEpoch || event.generatedPack()) return;
            if (!relevant(event, dependencies)) return;
            if (running) {
                // A change during a generation pass causes one coalesced follow-up.
                dirty = true;
                return;
            }
            if (scheduled == null) scheduled = scheduler.schedule(DEBOUNCE, () -> begin(epoch));
        }
    }

    /** Fences this ownership epoch and cancels any pending debounce. */
    public void fence(long epoch) {
        synchronized (gate) {
            if (epoch < ownershipEpoch) return;
            ownershipEpoch = epoch;
            active = false;
            dirty = false;
            cancelScheduledLocked();
        }
    }

    @Override public void close() {
        synchronized (gate) {
            active = false;
            dirty = false;
            cancelScheduledLocked();
        }
        scheduler.close();
    }

    /** Test/diagnostic view of whether an automatic pass is currently executing. */
    public boolean running() {
        synchronized (gate) { return running; }
    }

    private void begin(long expectedEpoch) {
        synchronized (gate) {
            scheduled = null;
            if (!active || ownershipEpoch != expectedEpoch || running) return;
            running = true;
            // Events arriving after this point are the one dirty follow-up for this pass.
            dirty = false;
        }
        boolean retryAdmission = false;
        try {
            PatchReloadCoordinator.ReloadOutcome outcome = reloadAction.reload(expectedEpoch);
            // An elected automatic pass can lose admission to the manual
            // command/admin operation. Keep that source change dirty so it is
            // retried once the current serialized operation has drained.
            retryAdmission = outcome != null && !outcome.started();
        } catch (RuntimeException ignored) {
            // A failed generation is represented by administration/reload status. The
            // controller must still release its lease so later edits can retry normally.
        } finally {
            synchronized (gate) {
                running = false;
                if (active && ownershipEpoch == expectedEpoch && (dirty || retryAdmission)) {
                    dirty = false;
                    if (scheduled == null) scheduled = scheduler.schedule(DEBOUNCE, () -> begin(expectedEpoch));
                } else if (!active || ownershipEpoch != expectedEpoch) {
                    dirty = false;
                }
            }
        }
    }

    private void cancelScheduledLocked() {
        if (scheduled != null) {
            scheduled.cancel();
            scheduled = null;
        }
    }

    private static boolean relevant(PatchworkSourceEvent event, GenerationDependencyIndex dependencies) {
        return switch (event.kind()) {
            case DEFINITION_CREATED, DEFINITION_MODIFIED, DEFINITION_REMOVED -> true;
            case PACK_REGISTERED, PACK_REMOVED, MONITOR_OVERFLOW, MONITOR_STOPPED -> true;
            case ASSET_CREATED, ASSET_MODIFIED, ASSET_REMOVED -> event.mutable()
                    && matchesAsset(event.sourcePackId(), event.assetPath(), dependencies);
        };
    }

    private static boolean matchesAsset(String packId, String path, GenerationDependencyIndex dependencies) {
        if (path == null || path.isBlank()) return true;
        if (dependencies.expandedTargets().contains(path) || dependencies.sourceAssets().contains(path)) return true;
        for (GenerationDependencyIndex.DefinitionDependency definition : dependencies.definitions()) {
            if (definition.sourcePackId().equals(packId) && definition.assetPath().equals(path)) return true;
        }
        for (GenerationDependencyIndex.GlobRoot root : dependencies.globRoots()) {
            if (path.startsWith(root.stablePrefix())) return true;
        }
        return false;
    }

    private static Scheduler daemonScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "patchwork-automatic-reload");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(factory);
        return new Scheduler() {
            @Override public Cancellable schedule(Duration delay, Runnable action) {
                ScheduledFuture<?> future = executor.schedule(action, delay.toNanos(), TimeUnit.NANOSECONDS);
                return () -> future.cancel(false);
            }
            @Override public void close() { executor.shutdownNow(); }
        };
    }
}
