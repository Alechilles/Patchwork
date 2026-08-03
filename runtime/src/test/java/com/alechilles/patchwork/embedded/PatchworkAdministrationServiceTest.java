package com.alechilles.patchwork.embedded;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.patchwork.generation.GeneratedPackManifest;
import com.alechilles.patchwork.generation.PatchGenerationService;
import com.alechilles.patchwork.generation.PatchStatusSnapshot;
import com.alechilles.patchwork.generation.GeneratedPackLayout;
import com.alechilles.patchwork.conflict.ConflictRecord;
import com.alechilles.patchwork.conflict.ConflictReport;
import com.alechilles.patchwork.engine.MutationEffect;
import com.alechilles.patchwork.reload.PatchReloadCoordinator;
import com.alechilles.patchwork.selftest.PatchworkSelfTestReloadHandle;
import com.alechilles.patchwork.selftest.PatchworkSelfTestRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers elected administration admission, deterministic planning, and safe rendering. */
final class PatchworkAdministrationServiceTest {
    @TempDir java.nio.file.Path temporary;
    @Test void inactiveAndFencedReloadNeverInvokeGeneration() {
        AtomicInteger generated = new AtomicInteger();
        PatchworkAdministrationService service = service(() -> { generated.incrementAndGet(); return plan("a.json", "a"); }, request -> outcome(1, List.of()));
        assertTrue(join(service.reload()).getFirst().contains("not started"));
        service.activate(4); service.fence(4);
        assertTrue(join(service.reload()).getFirst().contains("not started"));
        assertEquals(0, generated.get());
    }

    @Test void reloadBuildsSortedAdditionsAndNullRemovalsFromPublishedInventory() {
        List<PatchReloadCoordinator.ReloadPlan> observed = new ArrayList<>();
        PatchworkAdministrationService service = service(() -> plan("b.json", "new"), request -> {
            PatchReloadCoordinator.ReloadPlan plan = request.generator().get(); observed.add(plan);
            return outcome(2, List.of(new PatchReloadCoordinator.TargetOutcome("a.json", PatchReloadCoordinator.TargetState.REMOVED, "", "", null, null, false),
                    new PatchReloadCoordinator.TargetOutcome("b.json", PatchReloadCoordinator.TargetState.HOT_RELOADED, "", "", null, null, false)));
        }, () -> Map.of("a.json", "old".getBytes()));
        service.activate(2); service.seedPublishedInventory(plan("a.json", "old"));
        join(service.reload());
        assertEquals(List.of("a.json", "b.json"), observed.getFirst().updates().stream().map(PatchReloadCoordinator.TargetUpdate::target).toList());
        assertEquals(null, observed.getFirst().updates().getFirst().bytes());
        assertArrayEquals("new".getBytes(), observed.getFirst().updates().get(1).bytes());
    }

    @Test void rejectedTargetRetainsPreviouslyPublishedBytesDuringReload() {
        List<PatchReloadCoordinator.ReloadPlan> observed = new ArrayList<>();
        PatchworkAdministrationService service = service(
                () -> rejectedPlan("a.json", "b.json", "new"),
                request -> { observed.add(request.generator().get()); return outcome(5, List.of()); },
                () -> Map.of("a.json", "old".getBytes()));
        service.activate(5);
        service.seedPublishedInventory(plan("a.json", "old"));

        join(service.reload());

        assertEquals(List.of("b.json"), observed.getFirst().updates().stream()
                .map(PatchReloadCoordinator.TargetUpdate::target).toList());
        assertArrayEquals("new".getBytes(), observed.getFirst().updates().getFirst().bytes());
    }

    @Test void statusAndConflictActionRenderDeterministicValueRedactedRows() {
        ConflictRecord row = new ConflictRecord("Server/A.json", "/Value", MutationEffect.Kind.WRITE,
                new ConflictRecord.EffectRef("Pack:A", "first", "op", 0),
                new ConflictRecord.EffectRef("Pack:B", "second", "op", 1),
                ConflictRecord.Scope.CROSS_PACK, ConflictRecord.Classification.MATERIAL_OVERLAP);
        ConflictReport report = new ConflictReport(List.of(row));
        PatchworkAdministrationService service = service(() -> planWithConflicts(report), request -> outcome(6, List.of()));
        service.activate(6);
        service.seedStartup(6, planWithConflicts(report));

        assertTrue(String.join("\n", join(service.status())).contains("Conflicts: 1 material, 0 redundant"));
        List<String> lines = join(service.conflicts("Server/A.json"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Server/A.json")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("valueFingerprint")));
    }

    @Test void reloadAlwaysDiffsTheActualGeneratedInventoryInsteadOfTheCandidateBaseline() {
        List<PatchReloadCoordinator.ReloadPlan> observed = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Map<String, byte[]>> actual = new java.util.concurrent.atomic.AtomicReference<>(Map.of("a.json", "a".getBytes()));
        PatchworkAdministrationService service = service(() -> plan("a.json", "a"), request -> {
            observed.add(request.generator().get());
            return outcome(3, List.of());
        }, () -> actual.get());
        service.activate(1);
        join(service.reload());
        actual.set(Map.of("a.json", "b".getBytes()));
        service.activate(2);
        join(service.reload());
        service.activate(3);
        join(service.reload());
        assertEquals(1, observed.get(2).updates().size());
        assertArrayEquals("a".getBytes(), observed.get(2).updates().getFirst().bytes());
    }

    @Test void reloadPublishesActualPostPassInventoryAndMarksStatusUnknownWhenRescanFails() {
        java.util.concurrent.atomic.AtomicInteger scans = new java.util.concurrent.atomic.AtomicInteger();
        PatchworkAdministrationService service = service(() -> plan("new.json", "new"), request -> {
            request.generator().get();
            return new PatchReloadCoordinator.ReloadOutcome(true, 4, PatchReloadCoordinator.ManifestState.COMMIT_UNCERTAIN, List.of(
                    new PatchReloadCoordinator.TargetOutcome("old.json", PatchReloadCoordinator.TargetState.ROLLBACK_FAILED, "", "", null, null, true)), PatchReloadCoordinator.IntegrityState.UNCERTAIN, "");
        }, () -> {
            if (scans.incrementAndGet() > 1) throw new java.io.IOException("scan failure");
            return Map.of("old.json", "live".getBytes());
        });
        service.activate(4);
        join(service.reload());
        String status = String.join("\n", join(service.status()));
        assertTrue(status.contains("inventory unknown"));
        assertTrue(!status.contains("new.json"));
    }

    @Test void statusSortsAndRedactsReloadDiagnosticsAndEvidence() {
        PatchworkAdministrationService service = service(() -> plan("a.json", "a"), request -> outcome(3, List.of(
                new PatchReloadCoordinator.TargetOutcome("z.json", PatchReloadCoordinator.TargetState.FAILED, "", "SECRET_POINTER /private/path", null, java.nio.file.Path.of("SECRET_EVIDENCE"), false),
                new PatchReloadCoordinator.TargetOutcome("a.json", PatchReloadCoordinator.TargetState.ROLLBACK_FAILED, "", "SECRET_DOCUMENT", null, java.nio.file.Path.of("SECRET_BYTES"), true))));
        service.activate(3); join(service.reload());
        String status = String.join("\n", join(service.status()));
        assertTrue(status.indexOf("failed: z.json") < status.indexOf("rollback-failed: a.json"));
        assertTrue(!status.contains("SECRET_"));
        assertTrue(status.contains("rollback-failed") && status.contains("failed"));
    }

    @Test void reactivationClearsPriorCandidateReloadAndInventoryUntilCurrentEpochPublishes() {
        PatchworkAdministrationService service = service(() -> plan("a.json", "a"), request -> {
            request.generator().get(); return outcome(1, List.of(new PatchReloadCoordinator.TargetOutcome("a.json", PatchReloadCoordinator.TargetState.HOT_RELOADED, "", "", null, null, false)));
        }, () -> Map.of("a.json", "a".getBytes()));
        service.activate(1); join(service.reload());
        service.activate(2);
        String status = String.join("\n", join(service.status()));
        assertTrue(status.contains("generated inventory unknown"));
        assertTrue(!status.contains("Last reload:"));
        assertTrue(!status.contains("a.json"));
    }

    @Test void concurrentReloadIsRejectedAndFencePreventsBlockedPlanPublication() throws Exception {
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch continueGeneration = new CountDownLatch(1);
        AtomicInteger generated = new AtomicInteger();
        AtomicInteger executorCalls = new AtomicInteger();
        PatchworkAdministrationService service = service(() -> {
            generated.incrementAndGet(); generating.countDown();
            try { if (!continueGeneration.await(2, TimeUnit.SECONDS)) throw new AssertionError("test timeout"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            return plan("a.json", "a");
        }, request -> { executorCalls.incrementAndGet(); request.generator().get(); return outcome(8, List.of()); });
        service.activate(8);
        Thread first = Thread.ofVirtual().start(() -> join(service.reload()));
        assertTrue(generating.await(2, TimeUnit.SECONDS));
        assertTrue(join(service.reload()).getFirst().contains("not started"));
        service.fence(8);
        continueGeneration.countDown();
        first.join(2_000);
        service.drain(java.time.Duration.ofSeconds(1));
        assertEquals(1, generated.get());
        assertEquals(1, executorCalls.get());
    }

    @Test void temporaryReplayPauseRejectsNewWorkUntilTheSameEpochResumes() throws Exception {
        CountDownLatch generating = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        PatchworkAdministrationService service = service(() -> {
            generating.countDown(); try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            return plan("a.json", "a");
        }, request -> { request.generator().get(); return outcome(8, List.of()); });
        service.activate(8);
        Thread work = Thread.ofVirtual().start(() -> join(service.reload()));
        assertTrue(generating.await(2, TimeUnit.SECONDS));
        service.pause(8);
        assertTrue(join(service.reload()).getFirst().contains("not started"));
        release.countDown(); work.join(2_000); service.drain(java.time.Duration.ofSeconds(1));
        service.resume(8);
        assertTrue(!join(service.reload()).getFirst().contains("not started"));
    }

    @Test void concurrentSelfTestUsesTheSameAdmissionAndNeverStartsAnotherRunner() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runners = new AtomicInteger();
        PatchworkAdministrationService service = new PatchworkAdministrationService(() -> plan("a.json", "a"), () -> (PatchworkAdministrationService.ReloadExecutor) request -> outcome(6, List.of()), () -> pack -> {
            runners.incrementAndGet();
            entered.countDown();
            try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("test timeout"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            return new com.alechilles.patchwork.selftest.PatchworkSelfTestResult(java.nio.file.Path.of("run"), true, true, true, List.of(), "");
        });
        service.activate(6);
        Thread first = Thread.ofVirtual().start(() -> join(service.selfTest()));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(join(service.selfTest()).getFirst().contains("not started"));
        release.countDown();
        first.join(2_000);
        service.drain(java.time.Duration.ofSeconds(1));
        assertEquals(1, runners.get());
    }

    @Test void fenceCancelsBlockedSelfTestAndAllowsANewRunnerAfterDrain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch cancelled = new CountDownLatch(1);
        PatchworkAdministrationService service = new PatchworkAdministrationService(() -> plan("a.json", "a"), () -> request -> outcome(7, List.of()), () -> new SelfTestExecutor() {
            @Override public com.alechilles.patchwork.selftest.PatchworkSelfTestResult run(com.alechilles.patchwork.selftest.PatchworkSelfTestPack pack) {
                entered.countDown(); try { cancelled.await(2, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                return new com.alechilles.patchwork.selftest.PatchworkSelfTestResult(java.nio.file.Path.of("run"), false, true, true, List.of(), "");
            }
            @Override public void cancel() { cancelled.countDown(); }
        });
        service.activate(7); Thread worker = Thread.ofVirtual().start(() -> join(service.selfTest()));
        assertTrue(entered.await(2, TimeUnit.SECONDS)); service.fence(7); worker.join(2_000); service.drain(java.time.Duration.ofSeconds(1));
        assertEquals(0, cancelled.getCount()); service.activate(8); assertTrue(!join(service.selfTest()).getFirst().contains("not started"));
    }

    @Test void selfTestSupplierFailureReleasesAdmissionForTheNextCommand() {
        AtomicInteger supplied = new AtomicInteger();
        PatchworkAdministrationService service = new PatchworkAdministrationService(() -> plan("a.json", "a"), () -> request -> outcome(7, List.of()), () -> {
            if (supplied.getAndIncrement() == 0) throw new IllegalStateException("supplier failure");
            return pack -> new com.alechilles.patchwork.selftest.PatchworkSelfTestResult(java.nio.file.Path.of("run"), true, true, true, List.of(), "");
        });
        service.activate(7);
        assertTrue(join(service.selfTest()).getFirst().contains("failed"));
        service.drain(java.time.Duration.ofSeconds(1));
        List<String> lines = join(service.selfTest());
        assertTrue(lines.getFirst().contains("completed") && lines.getFirst().contains("reload restart-required"));
        assertTrue(lines.get(1).equals("Cleanup: complete"));
    }

    @Test void selfTestExecutorFailureReleasesAdmissionForTheNextCommand() {
        AtomicInteger runs = new AtomicInteger();
        PatchworkAdministrationService service = new PatchworkAdministrationService(() -> plan("a.json", "a"), () -> request -> outcome(7, List.of()), () -> pack -> {
            if (runs.getAndIncrement() == 0) throw new IllegalStateException("run failure");
            return new com.alechilles.patchwork.selftest.PatchworkSelfTestResult(java.nio.file.Path.of("run"), true, true, true, List.of(), "");
        });
        service.activate(7);
        assertTrue(join(service.selfTest()).getFirst().contains("failed"));
        service.drain(java.time.Duration.ofSeconds(1));
        assertTrue(join(service.selfTest()).getFirst().contains("completed"));
    }

    @Test void selfTestReportsEachCompletedFixtureOutcome() {
        PatchworkAdministrationService service = new PatchworkAdministrationService(() -> plan("a.json", "a"), () -> request -> outcome(7, List.of()), () -> pack ->
                new com.alechilles.patchwork.selftest.PatchworkSelfTestResult(java.nio.file.Path.of("run"), true, true, true, List.of(), "", false, false,
                        com.alechilles.patchwork.selftest.PatchworkSelfTestResult.GenerationOutcome.FAILED,
                        PatchworkSelfTestReloadHandle.ReloadOutcome.RESTART_REQUIRED, List.of(), List.of(
                                new com.alechilles.patchwork.selftest.PatchworkSelfTestResult.CaseOutcome("Server/PatchworkSelfTest/add.json", true, true, List.of(), ""),
                                new com.alechilles.patchwork.selftest.PatchworkSelfTestResult.CaseOutcome("Server/PatchworkSelfTest/condition.json", true, false, List.of(), ""))));

        service.activate(7);

        assertEquals(List.of("Patchwork self-test: failed (reload restart-required)", "Add: passed", "Condition (ModData): failed", "Cleanup: complete"), join(service.selfTest()));
    }

    @Test void fenceCancelsAnActualRunnerBlockedAtReloadAndDrainsWithoutStaleWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch released = new CountDownLatch(1);
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); java.nio.file.Path production = java.nio.file.Files.createDirectories(layout.generatedRoot()); java.nio.file.Files.writeString(production.resolve("manifest.json"), "production");
        PatchworkSelfTestReloadHandle handle = new PatchworkSelfTestReloadHandle() {
            @Override public ReloadOutcome reloadIsolated(IsolatedGeneration generation) { entered.countDown(); try { released.await(2, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); } return ReloadOutcome.RESTART_REQUIRED; }
            @Override public void cancel() { released.countDown(); }
        };
        AtomicInteger supplied = new AtomicInteger();
        PatchworkAdministrationService service = new PatchworkAdministrationService(() -> plan("a.json", "a"), () -> request -> outcome(12, List.of()), () -> {
            PatchworkSelfTestRunner runner = new PatchworkSelfTestRunner(layout, supplied.getAndIncrement() == 0 ? handle : null);
            return new SelfTestExecutor() {
                @Override public com.alechilles.patchwork.selftest.PatchworkSelfTestResult run(com.alechilles.patchwork.selftest.PatchworkSelfTestPack pack) { return runner.run(pack); }
                @Override public void cancel() { runner.cancel(); }
            };
        });
        service.activate(12); Thread worker = Thread.ofVirtual().start(() -> join(service.selfTest()));
        assertTrue(entered.await(30, TimeUnit.SECONDS)); service.fence(12); worker.join(30_000); assertFalse(worker.isAlive()); service.drain(java.time.Duration.ofSeconds(30));
        assertEquals("production", java.nio.file.Files.readString(production.resolve("manifest.json"))); service.activate(13); assertTrue(join(service.selfTest()).getFirst().contains("completed")); assertEquals(2, supplied.get());
    }

    @Test void immutableStatusSnapshotRendersEverySafeCategoryInOrderWithBoundedDetails() {
        List<PatchReloadCoordinator.TargetOutcome> outcomes = new ArrayList<>();
        for (PatchReloadCoordinator.TargetState state : PatchReloadCoordinator.TargetState.values()) outcomes.add(
                new PatchReloadCoordinator.TargetOutcome(state.name().toLowerCase() + ".json", state, "adapter", "SECRET_POINTER", null, java.nio.file.Path.of("SECRET_EVIDENCE"), false));
        for (int index = 0; index < 40; index++) outcomes.add(new PatchReloadCoordinator.TargetOutcome("extra-" + index + ".json", PatchReloadCoordinator.TargetState.FAILED, "", "SECRET_DOC", null, null, false));
        PatchworkAdministrationSnapshot snapshot = new PatchworkAdministrationSnapshot(true, 9,
                java.util.Map.of("candidates", List.of(java.util.Map.of("providerId", "winner", "active", true, "reason", "elected", "runtimeVersion", "2.0.0", "origin", "STANDALONE", "providerPluginId", "provider", "providerPluginVersion", "3.1", "coordinatorAbi", 1, "sourceJarPath", java.nio.file.Path.of("runtime.jar")), java.util.Map.of("providerId", "old", "active", false, "reason", "lower-election-priority")),
                        "contributions", List.of(java.util.Map.of("contributionId", "host:one"))),
                "Neutral", List.of("Legacy B", "Legacy A"), 8, List.of("generated.json"), true,
                new PatchStatusSnapshot(List.of("SECRET_EXPECTED"), java.util.Map.of("bad.json", "SECRET_DOCUMENT"), List.of("SECRET_SOURCE")),
                outcome(9, outcomes));
        List<String> lines = snapshot.render(); String text = String.join("\n", lines);
        assertTrue(text.contains("Active runtime: winner") && text.contains("version 2.0.0") && text.contains("origin STANDALONE") && text.contains("plugin provider@3.1") && text.contains("ABI 1") && text.contains("runtime.jar") && text.contains("Passive runtime: old") && text.contains("Contribution: host:one"));
        assertTrue(text.contains("Neutral root: Neutral") && text.indexOf("Legacy root: Legacy A") < text.indexOf("Legacy root: Legacy B"));
        for (String category : List.of("generated", "removed", "hot-reloaded", "adapter-reloaded", "restart-required", "stale", "rollback-failed", "skipped", "failed")) assertTrue(text.contains(category));
        assertTrue(text.contains("Additional target rows:"));
        assertTrue(!text.contains("SECRET_"));
    }

    private static PatchworkAdministrationService service(java.util.function.Supplier<PatchGenerationService.GenerationPlan> generation,
                                                           PatchworkAdministrationService.ReloadExecutor executor) {
        return new PatchworkAdministrationService(generation, () -> executor, () -> { throw new AssertionError("self-test must not run"); });
    }
    private static PatchworkAdministrationService service(java.util.function.Supplier<PatchGenerationService.GenerationPlan> generation,
                                                           PatchworkAdministrationService.ReloadExecutor executor,
                                                           GeneratedInventorySnapshotter snapshots) {
        return new PatchworkAdministrationService(generation, () -> executor, () -> { throw new AssertionError("self-test must not run"); }, snapshots);
    }
    private static List<String> join(java.util.concurrent.CompletionStage<List<String>> stage) { return stage.toCompletableFuture().join(); }
    private static PatchGenerationService.GenerationPlan plan(String target, String bytes) {
        List<GeneratedPackManifest.Entry> entries = List.of(new GeneratedPackManifest.Entry(target, bytes.getBytes()));
        return new PatchGenerationService.GenerationPlan(entries, new PatchStatusSnapshot(List.of(), java.util.Map.of(), List.of()), new GeneratedPackManifest(entries), List.of("Example:Pack"));
    }
    private static PatchGenerationService.GenerationPlan rejectedPlan(String rejected, String target, String bytes) {
        List<GeneratedPackManifest.Entry> entries = List.of(new GeneratedPackManifest.Entry(target, bytes.getBytes()));
        PatchStatusSnapshot status = new PatchStatusSnapshot(List.of(), Map.of(rejected, "Conflict rejected"), List.of());
        return new PatchGenerationService.GenerationPlan(entries, status, new GeneratedPackManifest(entries), List.of("Example:Pack"));
    }
    private static PatchGenerationService.GenerationPlan planWithConflicts(ConflictReport report) {
        List<GeneratedPackManifest.Entry> entries = List.of(new GeneratedPackManifest.Entry("Server/A.json", "a".getBytes()));
        PatchStatusSnapshot status = new PatchStatusSnapshot(List.of(), Map.of(), List.of(), report);
        return new PatchGenerationService.GenerationPlan(entries, status, new GeneratedPackManifest(entries), report);
    }
    private static PatchReloadCoordinator.ReloadOutcome outcome(long epoch, List<PatchReloadCoordinator.TargetOutcome> targets) {
        return new PatchReloadCoordinator.ReloadOutcome(true, epoch, PatchReloadCoordinator.ManifestState.COMMITTED, targets, PatchReloadCoordinator.IntegrityState.RECONCILED, "SECRET_DIAGNOSTIC");
    }
}
