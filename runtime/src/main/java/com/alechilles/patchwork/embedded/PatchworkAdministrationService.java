package com.alechilles.patchwork.embedded;

import com.alechilles.patchwork.command.PatchworkCommandActions;
import com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry;
import com.alechilles.patchwork.generation.PatchGenerationService;
import com.alechilles.patchwork.generation.PatchStatusSnapshot;
import com.alechilles.patchwork.generation.StartupPackPublisher;
import com.alechilles.patchwork.reload.PatchReloadCoordinator;
import com.alechilles.patchwork.selftest.PatchworkSelfTestPack;
import com.alechilles.patchwork.selftest.PatchworkSelfTestResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Elected-host administration coordinator. It admits only one active operation and owns the
 * mutable generated inventory used to turn a fresh plan into deterministic reload updates.
 */
final class PatchworkAdministrationService implements PatchworkCommandActions {
    private final Object gate = new Object();
    private final Supplier<PatchGenerationService.GenerationPlan> generation;
    private final Supplier<ReloadExecutor> reloads;
    private final Supplier<SelfTestExecutor> selfTests;
    private final GeneratedInventorySnapshotter snapshots;
    private boolean active;
    private boolean paused;
    private boolean running;
    private SelfTestExecutor activeSelfTest;
    private long epoch;
    private Map<String, byte[]> inventory = Map.of();
    private boolean inventoryKnown;
    private PatchReloadCoordinator.ReloadOutcome lastReload;
    private long generationEpoch;
    private PatchStatusSnapshot generationStatus = new PatchStatusSnapshot(List.of(), Map.of(), List.of());
    private String neutralRoot = "unavailable";
    private List<String> legacyRoots = List.of();

    PatchworkAdministrationService(Supplier<PatchGenerationService.GenerationPlan> generation,
                                  Supplier<ReloadExecutor> reloads,
                                  Supplier<SelfTestExecutor> selfTests) {
        this(generation, reloads, selfTests, () -> Map.of());
    }

    PatchworkAdministrationService(Supplier<PatchGenerationService.GenerationPlan> generation,
                                  Supplier<ReloadExecutor> reloads,
                                  Supplier<SelfTestExecutor> selfTests,
                                  GeneratedInventorySnapshotter snapshots) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.reloads = Objects.requireNonNull(reloads, "reloads");
        this.selfTests = Objects.requireNonNull(selfTests, "selfTests");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    void activate(long value) {
        synchronized (gate) {
            epoch = value; active = true; paused = false;
            inventory = Map.of(); inventoryKnown = false; lastReload = null;
            generationEpoch = 0; generationStatus = new PatchStatusSnapshot(List.of(), Map.of(), List.of());
        }
    }

    /** Seeds the diff baseline only after startup publication has made this plan live. */
    void seedPublishedInventory(PatchGenerationService.GenerationPlan plan) {
        seedPublishedInventory(plan, epoch);
    }

    /** Records a published startup plan and its status at the elected ownership epoch. */
    void seedStartup(long publishedEpoch, PatchGenerationService.GenerationPlan plan) {
        Map<String, byte[]> published = inventory(Objects.requireNonNull(plan, "plan"));
        synchronized (gate) { if (active && epoch == publishedEpoch) { inventory = published; inventoryKnown = true; generationEpoch = publishedEpoch; generationStatus = plan.status(); } }
    }

    void seedPublishedInventory(PatchGenerationService.GenerationPlan plan, long publishedEpoch) { seedStartup(publishedEpoch, plan); }

    /** Supplies root labels captured by composition without exposing condition source details. */
    void configureRoots(String neutral, List<String> eligibleLegacy) {
        synchronized (gate) { neutralRoot = neutral; legacyRoots = eligibleLegacy == null ? List.of() : List.copyOf(eligibleLegacy); }
    }

    void fence(long value) {
        SelfTestExecutor cancelling = null;
        synchronized (gate) {
            if (value >= epoch) { active = false; cancelling = activeSelfTest; }
        }
        if (cancelling != null) cancelling.cancel();
    }

    /** Temporarily rejects new work while a host replaces its contribution routes for this lease. */
    void pause(long value) { synchronized (gate) { if (active && epoch == value) paused = true; } }
    void resume(long value) { synchronized (gate) { if (active && epoch == value) paused = false; } }

    void drain(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (gate) {
            while (running) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("Timed out draining Patchwork administration work.");
                try { gate.wait(Math.max(1L, remaining / 1_000_000L)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted draining Patchwork administration work.", failure); }
            }
        }
    }

    @Override public CompletionStage<List<String>> status() {
        PatchworkAdministrationSnapshot snapshot;
        synchronized (gate) {
            snapshot = new PatchworkAdministrationSnapshot(active, epoch, Map.of(), neutralRoot, legacyRoots,
                    generationEpoch, new ArrayList<>(inventory.keySet()), inventoryKnown, generationStatus, lastReload);
        }
        snapshot = new PatchworkAdministrationSnapshot(snapshot.active(), snapshot.epoch(), PatchworkCoordinatorRegistry.adminSnapshot(),
                snapshot.neutralRoot(), snapshot.legacyRoots(), snapshot.generationEpoch(), snapshot.generatedTargets(), snapshot.inventoryKnown(), snapshot.generationStatus(), snapshot.reload());
        return CompletableFuture.completedFuture(snapshot.render());
    }

    @Override public CompletionStage<List<String>> reload() {
        long admittedEpoch = admit();
        if (admittedEpoch < 0) return CompletableFuture.completedFuture(List.of("Patchwork reload was not started: runtime is inactive or busy."));
        try {
            PatchReloadCoordinator.ReloadOutcome outcome = reloads.get().reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> {
                if (!stillActive(admittedEpoch)) throw new IllegalStateException("Patchwork reload was fenced.");
                Map<String, byte[]> prior = snapshotOrFail();
                if (!stillActive(admittedEpoch)) throw new IllegalStateException("Patchwork reload was fenced.");
                PatchGenerationService.GenerationPlan plan = generation.get();
                Map<String, byte[]> next = inventory(plan);
                synchronized (gate) {
                    if (!active || epoch != admittedEpoch) throw new IllegalStateException("Patchwork reload was fenced.");
                    generationEpoch = admittedEpoch;
                    generationStatus = plan.status();
                }
                List<PatchReloadCoordinator.TargetUpdate> updates = updates(prior, next);
                if (!stillActive(admittedEpoch)) throw new IllegalStateException("Patchwork reload was fenced.");
                return new PatchReloadCoordinator.ReloadPlan(StartupPackPublisher.hytaleManifest(plan.sourcePackIds()), updates);
            }));
            Map<String, byte[]> actual = null;
            boolean known = false;
            try { actual = snapshots.snapshot(); known = true; } catch (Exception ignored) { }
            synchronized (gate) {
                if (active && epoch == admittedEpoch) {
                    inventory = known ? actual : Map.of(); inventoryKnown = known;
                    lastReload = outcome;
                }
            }
            return CompletableFuture.completedFuture(reloadLines(outcome));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(List.of("Patchwork reload failed; inspect server diagnostics."));
        } finally { release(); }
    }

    @Override public CompletionStage<List<String>> selfTest() {
        long admittedEpoch = admit();
        if (admittedEpoch < 0) return CompletableFuture.completedFuture(List.of("Patchwork self-test was not started: runtime is inactive or busy."));
        try {
            SelfTestExecutor executor = selfTests.get();
            synchronized (gate) { activeSelfTest = executor; }
            if (!stillActive(admittedEpoch)) executor.cancel();
            PatchworkSelfTestResult result = executor.run(PatchworkSelfTestPack.standard());
            String state = result.completed() ? "completed" : result.cancelled() ? "cancelled" : "failed";
            String category = result.reloadOutcome().name().toLowerCase().replace('_', '-');
            return CompletableFuture.completedFuture(List.of("Patchwork self-test: " + state + " (reload " + category + ")", "Cleanup: " + (result.cleanupSucceeded() ? "complete" : "incomplete")));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(List.of("Patchwork self-test failed; inspect server diagnostics."));
        } finally { synchronized (gate) { activeSelfTest = null; } release(); }
    }

    private long admit() {
        synchronized (gate) { if (!active || paused || running) return -1L; running = true; return epoch; }
    }
    private boolean stillActive(long expectedEpoch) { synchronized (gate) { return active && epoch == expectedEpoch; } }
    private void release() { synchronized (gate) { running = false; gate.notifyAll(); } }
    private Map<String, byte[]> snapshotOrFail() {
        try { return snapshots.snapshot(); }
        catch (Exception failure) { throw new IllegalStateException("Generated inventory is unavailable."); }
    }
    private static Map<String, byte[]> inventory(PatchGenerationService.GenerationPlan plan) {
        Map<String, byte[]> next = new LinkedHashMap<>();
        plan.entries().stream().sorted(Comparator.comparing(entry -> entry.target())).forEach(entry -> next.put(entry.target(), entry.bytes()));
        return Map.copyOf(next);
    }
    private static List<PatchReloadCoordinator.TargetUpdate> updates(Map<String, byte[]> old, Map<String, byte[]> next) {
        return java.util.stream.Stream.concat(old.keySet().stream(), next.keySet().stream()).distinct().sorted()
                .filter(target -> !java.util.Arrays.equals(old.get(target), next.get(target)))
                .map(target -> new PatchReloadCoordinator.TargetUpdate(target, next.get(target))).toList();
    }
    private static List<String> reloadLines(PatchReloadCoordinator.ReloadOutcome outcome) {
        List<String> lines = new ArrayList<>();
        lines.add("Patchwork reload " + (outcome.started() ? "started" : "not started") + " (epoch " + outcome.epoch() + ")");
        outcome.targets().stream().sorted(Comparator.comparing(PatchReloadCoordinator.TargetOutcome::target))
                .limit(32).forEach(target -> lines.add(target.target() + ": " + target.state()));
        if (!outcome.diagnostic().isBlank()) lines.add("Reload reported a diagnostic; inspect server logs.");
        return List.copyOf(lines);
    }

    /** Narrow synchronous executor seam; production delegates directly to one reload coordinator. */
    @FunctionalInterface interface ReloadExecutor {
        PatchReloadCoordinator.ReloadOutcome reload(PatchReloadCoordinator.ReloadRequest request);
    }
}
