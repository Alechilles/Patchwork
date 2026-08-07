package com.alechilles.patchwork.embedded;

import com.alechilles.patchwork.command.PatchworkCommandActions;
import com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry;
import com.alechilles.patchwork.generation.PatchGenerationService;
import com.alechilles.patchwork.generation.PatchStatusSnapshot;
import com.alechilles.patchwork.conflict.ConflictRecord;
import com.alechilles.patchwork.conflict.ConflictReport;
import com.alechilles.patchwork.generation.StartupPackPublisher;
import com.alechilles.patchwork.reload.PatchReloadCoordinator;
import com.alechilles.patchwork.selftest.PatchworkSelfTestPack;
import com.alechilles.patchwork.selftest.PatchworkSelfTestResult;
import com.alechilles.patchwork.telemetry.PatchworkTelemetry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private final PatchworkTelemetry telemetry;
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
    private ConflictReport conflictReport = ConflictReport.empty();
    private String neutralRoot = "unavailable";
    private List<String> legacyRoots = List.of();
    private volatile java.util.function.Consumer<com.alechilles.patchwork.generation.GenerationDependencyIndex> dependencySink = ignored -> { };

    PatchworkAdministrationService(Supplier<PatchGenerationService.GenerationPlan> generation,
                                  Supplier<ReloadExecutor> reloads,
                                  Supplier<SelfTestExecutor> selfTests) {
        this(generation, reloads, selfTests, () -> Map.of(), PatchworkTelemetry.disabled());
    }

    PatchworkAdministrationService(Supplier<PatchGenerationService.GenerationPlan> generation,
                                  Supplier<ReloadExecutor> reloads,
                                  Supplier<SelfTestExecutor> selfTests,
                                  GeneratedInventorySnapshotter snapshots) {
        this(generation, reloads, selfTests, snapshots, PatchworkTelemetry.disabled());
    }

    PatchworkAdministrationService(Supplier<PatchGenerationService.GenerationPlan> generation,
                                  Supplier<ReloadExecutor> reloads,
                                  Supplier<SelfTestExecutor> selfTests,
                                  GeneratedInventorySnapshotter snapshots,
                                  PatchworkTelemetry telemetry) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.reloads = Objects.requireNonNull(reloads, "reloads");
        this.selfTests = Objects.requireNonNull(selfTests, "selfTests");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    void activate(long value) {
        synchronized (gate) {
            epoch = value; active = true; paused = false;
            inventory = Map.of(); inventoryKnown = false; lastReload = null;
            generationEpoch = 0; generationStatus = new PatchStatusSnapshot(List.of(), Map.of(), List.of());
            conflictReport = ConflictReport.empty();
        }
    }

    /** Seeds the diff baseline only after startup publication has made this plan live. */
    void seedPublishedInventory(PatchGenerationService.GenerationPlan plan) {
        seedPublishedInventory(plan, epoch);
    }

    /** Records a published startup plan and its status at the elected ownership epoch. */
    void seedStartup(long publishedEpoch, PatchGenerationService.GenerationPlan plan) {
        Map<String, byte[]> published = inventory(Objects.requireNonNull(plan, "plan"));
        synchronized (gate) { if (active && epoch == publishedEpoch) { inventory = published; inventoryKnown = true; generationEpoch = publishedEpoch; generationStatus = status(plan); conflictReport = plan.conflicts(); } }
        dependencySink.accept(plan.dependencies());
    }

    void seedPublishedInventory(PatchGenerationService.GenerationPlan plan, long publishedEpoch) { seedStartup(publishedEpoch, plan); }

    /** Supplies root labels captured by composition without exposing condition source details. */
    void configureRoots(String neutral, List<String> eligibleLegacy) {
        synchronized (gate) { neutralRoot = neutral; legacyRoots = eligibleLegacy == null ? List.of() : List.copyOf(eligibleLegacy); }
    }

    /** Installs the elected host's dependency metadata sink for automatic reloads. */
    void setDependencySink(java.util.function.Consumer<com.alechilles.patchwork.generation.GenerationDependencyIndex> sink) {
        dependencySink = Objects.requireNonNull(sink, "sink");
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
        telemetry.recordUsage("status_viewed", null);
        PatchworkAdministrationSnapshot snapshot;
        synchronized (gate) {
            snapshot = new PatchworkAdministrationSnapshot(active, epoch, Map.of(), neutralRoot, legacyRoots,
                    generationEpoch, new ArrayList<>(inventory.keySet()), inventoryKnown, generationStatus, lastReload);
        }
        snapshot = new PatchworkAdministrationSnapshot(snapshot.active(), snapshot.epoch(), PatchworkCoordinatorRegistry.adminSnapshot(),
                snapshot.neutralRoot(), snapshot.legacyRoots(), snapshot.generationEpoch(), snapshot.generatedTargets(), snapshot.inventoryKnown(), snapshot.generationStatus(), snapshot.reload());
        return CompletableFuture.completedFuture(snapshot.render());
    }

    @Override public CompletionStage<List<String>> conflicts(String target) {
        telemetry.recordUsage("conflicts_viewed", null);
        ConflictReport snapshot;
        synchronized (gate) { snapshot = conflictReport; }
        return CompletableFuture.completedFuture(renderConflicts(snapshot, target));
    }

    @Override public CompletionStage<List<String>> reload() {
        long admittedEpoch = admit();
        if (admittedEpoch < 0) return CompletableFuture.completedFuture(List.of("Patchwork reload was not started: runtime is inactive or busy."));
        long startedAt = System.nanoTime();
        telemetry.recordUsage("reload_requested", "command");
        try {
            PatchReloadCoordinator.ReloadOutcome outcome = reloads.get().reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> buildPlan(admittedEpoch)));
            recordOutcome(admittedEpoch, outcome);
            reportReloadOutcome(outcome, elapsedMs(startedAt), "command");
            return CompletableFuture.completedFuture(reloadLines(outcome));
        } catch (RuntimeException failure) {
            telemetry.recordError("reload_failed", failure, "generation");
            telemetry.recordPerformance("reload_duration", elapsedMs(startedAt), "command");
            return CompletableFuture.completedFuture(List.of("Patchwork reload failed; inspect server diagnostics."));
        } finally { release(); }
    }

    /** Starts a generation pass from an elected automatic source callback. */
    PatchReloadCoordinator.ReloadOutcome automaticReload(long expectedOwnershipEpoch) {
        long admittedEpoch = admit(expectedOwnershipEpoch);
        if (admittedEpoch < 0) return notStarted("Automatic reload owner is stale or busy.");
        long startedAt = System.nanoTime();
        telemetry.recordUsage("reload_requested", "automatic");
        try {
            PatchReloadCoordinator.ReloadOutcome outcome = reloads.get().elected(admittedEpoch, () -> buildPlan(admittedEpoch));
            recordOutcome(admittedEpoch, outcome);
            reportReloadOutcome(outcome, elapsedMs(startedAt), "automatic");
            return outcome;
        } catch (RuntimeException failure) {
            telemetry.recordError("reload_failed", failure, "generation");
            telemetry.recordPerformance("reload_duration", elapsedMs(startedAt), "automatic");
            return notStarted("Automatic reload failed: " + (failure.getMessage() == null ? "generation error" : failure.getMessage()));
        } finally { release(); }
    }

    private PatchReloadCoordinator.ReloadPlan buildPlan(long admittedEpoch) {
        if (!stillActive(admittedEpoch)) throw new IllegalStateException("Patchwork reload was fenced.");
        Map<String, byte[]> prior = snapshotOrFail();
        if (!stillActive(admittedEpoch)) throw new IllegalStateException("Patchwork reload was fenced.");
        PatchGenerationService.GenerationPlan plan = generation.get();
        Map<String, byte[]> next = inventory(plan, prior);
        synchronized (gate) {
            if (!active || epoch != admittedEpoch) throw new IllegalStateException("Patchwork reload was fenced.");
            generationEpoch = admittedEpoch;
            generationStatus = status(plan);
            conflictReport = plan.conflicts();
        }
        dependencySink.accept(plan.dependencies());
        List<PatchReloadCoordinator.TargetUpdate> updates = updates(prior, next);
        if (!stillActive(admittedEpoch)) throw new IllegalStateException("Patchwork reload was fenced.");
        return new PatchReloadCoordinator.ReloadPlan(StartupPackPublisher.hytaleManifest(plan.sourcePackIds()), updates);
    }

    private void recordOutcome(long admittedEpoch, PatchReloadCoordinator.ReloadOutcome outcome) {
        Map<String, byte[]> actual = null;
        boolean known = false;
        try { actual = snapshots.snapshot(); known = true; } catch (Exception ignored) { }
        synchronized (gate) {
            if (active && epoch == admittedEpoch) {
                inventory = known ? actual : Map.of(); inventoryKnown = known;
                lastReload = outcome;
            }
        }
    }

    @Override public CompletionStage<List<String>> selfTest() {
        long admittedEpoch = admit();
        if (admittedEpoch < 0) return CompletableFuture.completedFuture(List.of("Patchwork self-test was not started: runtime is inactive or busy."));
        telemetry.recordUsage("self_test_requested", null);
        try {
            SelfTestExecutor executor = selfTests.get();
            synchronized (gate) { activeSelfTest = executor; }
            if (!stillActive(admittedEpoch)) executor.cancel();
            PatchworkSelfTestResult result = executor.run(PatchworkSelfTestPack.standard());
            return CompletableFuture.completedFuture(selfTestLines(result));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(List.of("Patchwork self-test failed; inspect server diagnostics."));
        } finally { synchronized (gate) { activeSelfTest = null; } release(); }
    }

    private long admit() {
        synchronized (gate) { if (!active || paused || running) return -1L; running = true; return epoch; }
    }
    private long admit(long expectedEpoch) {
        synchronized (gate) { if (!active || paused || running || epoch != expectedEpoch) return -1L; running = true; return epoch; }
    }
    private boolean stillActive(long expectedEpoch) { synchronized (gate) { return active && epoch == expectedEpoch; } }
    private void release() { synchronized (gate) { running = false; gate.notifyAll(); } }
    private void reportReloadOutcome(PatchReloadCoordinator.ReloadOutcome outcome, int durationMs, String trigger) {
        boolean success = outcome.started()
                && outcome.manifestState() != PatchReloadCoordinator.ManifestState.COMMIT_UNCERTAIN
                && outcome.integrityState() == PatchReloadCoordinator.IntegrityState.RECONCILED
                && outcome.targets().stream().noneMatch(target -> target.state() == PatchReloadCoordinator.TargetState.FAILED
                || target.state() == PatchReloadCoordinator.TargetState.ROLLBACK_FAILED);
        telemetry.recordLifecycle("reload_completed", durationMs, success, trigger);
        telemetry.recordPerformance("reload_duration", durationMs, trigger);
        if (success) telemetry.recordStats("reload_completed", null);
        else telemetry.recordError("reload_failed", null, "integrity");
    }
    private static int elapsedMs(long startedAt) {
        long millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, millis));
    }
    private PatchReloadCoordinator.ReloadOutcome notStarted(String diagnostic) {
        synchronized (gate) {
            return new PatchReloadCoordinator.ReloadOutcome(false, epoch, PatchReloadCoordinator.ManifestState.NOT_ATTEMPTED,
                    List.of(), PatchReloadCoordinator.IntegrityState.NOT_ATTEMPTED, diagnostic);
        }
    }
    private Map<String, byte[]> snapshotOrFail() {
        try { return snapshots.snapshot(); }
        catch (Exception failure) { throw new IllegalStateException("Generated inventory is unavailable."); }
    }
    private static Map<String, byte[]> inventory(PatchGenerationService.GenerationPlan plan) {
        Map<String, byte[]> next = new LinkedHashMap<>();
        plan.entries().stream().sorted(Comparator.comparing(entry -> entry.target())).forEach(entry -> next.put(entry.target(), entry.bytes()));
        return Map.copyOf(next);
    }
    private static PatchStatusSnapshot status(PatchGenerationService.GenerationPlan plan) {
        PatchStatusSnapshot status = plan.status();
        if (status.conflicts() == plan.conflicts() || status.conflicts().records().equals(plan.conflicts().records())) return status;
        return new PatchStatusSnapshot(status.skipped(), status.rejectedTargets(), status.scanFailures(), plan.conflicts());
    }
    private static Map<String, byte[]> inventory(PatchGenerationService.GenerationPlan plan, Map<String, byte[]> prior) {
        Map<String, byte[]> next = new LinkedHashMap<>(inventory(plan));
        plan.status().rejectedTargets().keySet().forEach(target -> {
            byte[] bytes = prior.get(target);
            if (bytes != null) next.put(target, bytes.clone());
        });
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
    private static List<String> selfTestLines(PatchworkSelfTestResult result) {
        String state = result.completed() ? "completed" : result.cancelled() ? "cancelled" : "failed";
        String category = result.reloadOutcome().name().toLowerCase().replace('_', '-');
        List<String> lines = new ArrayList<>();
        lines.add("Patchwork self-test: " + state + " (reload " + category + ")");
        result.caseOutcomes().forEach(outcome -> lines.add(selfTestCaseLabel(outcome.target()) + ": " + (outcome.passed() ? "passed" : "failed")));
        if (!result.completed() && result.caseOutcomes().isEmpty()) lines.add("Fixtures: none completed");
        lines.add("Cleanup: " + (result.cleanupSucceeded() ? "complete" : "incomplete"));
        return List.copyOf(lines);
    }

    private static List<String> renderConflicts(ConflictReport report, String target) {
        List<ConflictRecord> rows = report.forTarget(target);
        ConflictReport scoped = target == null ? report : new ConflictReport(rows);
        List<String> lines = new ArrayList<>();
        lines.add("Conflicts: " + scoped.materialCount() + " material, " + scoped.redundantCount() + " redundant");
        if (target != null) lines.add("Target filter: " + target + " (" + rows.size() + " row(s))");
        rows.stream().limit(PatchworkAdministrationSnapshot.MAX_DETAIL_ROWS).forEach(row -> lines.add(formatConflict(row)));
        if (rows.size() > PatchworkAdministrationSnapshot.MAX_DETAIL_ROWS) {
            lines.add("Additional conflict rows: " + (rows.size() - PatchworkAdministrationSnapshot.MAX_DETAIL_ROWS));
        }
        return List.copyOf(lines);
    }

    private static String formatConflict(ConflictRecord row) {
        ConflictRecord.EffectRef earlier = row.earlier();
        ConflictRecord.EffectRef later = row.later();
        return row.target() + " " + row.path() + " (" + row.effectKind().name().toLowerCase(Locale.ROOT).replace('_', '-')
                + ", " + row.scope().name().toLowerCase(Locale.ROOT).replace('_', '-') + ", " + row.classification().name().toLowerCase(Locale.ROOT).replace('_', '-')
                + ") earlier " + earlier.sourcePackId() + ":" + earlier.patchId() + ":" + earlier.operationId() + "@" + earlier.operationOrder()
                + ", later " + later.sourcePackId() + ":" + later.patchId() + ":" + later.operationId() + "@" + later.operationOrder();
    }
    private static String selfTestCaseLabel(String target) {
        String name = target.substring(target.lastIndexOf('/') + 1);
        if (name.endsWith(".json")) name = name.substring(0, name.length() - ".json".length());
        if ("condition".equals(name)) return "Condition (ModData)";
        return Character.toUpperCase(name.charAt(0)) + name.substring(1).replace('-', ' ');
    }

    /** Narrow synchronous executor seam; production delegates directly to one reload coordinator. */
    @FunctionalInterface interface ReloadExecutor {
        PatchReloadCoordinator.ReloadOutcome reload(PatchReloadCoordinator.ReloadRequest request);
        default PatchReloadCoordinator.ReloadOutcome elected(long expectedOwnershipEpoch, Supplier<PatchReloadCoordinator.ReloadPlan> generator) {
            return reload(new PatchReloadCoordinator.ReloadRequest(PatchReloadCoordinator.Trigger.SOURCE_EDIT,
                    "patchwork.elected", generator, expectedOwnershipEpoch));
        }
    }
}
