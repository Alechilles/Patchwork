package com.alechilles.patchwork.reload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises target-local live reload commits and recovery. */
final class PatchReloadCoordinatorTest {
    @TempDir Path temporary;

    @Test
    void authorizedReloadCommitsManifestBeforeWritingAndConfirmingBuiltInTargets() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated"));
        PatchReloadTracker tracker = new PatchReloadTracker();
        AtomicBoolean manifestPresent = new AtomicBoolean();
        HytalePatchTargetAdapter builtIn = adapter("built-in", target -> {
            manifestPresent.set(Files.exists(root.resolve("patchwork-manifest.json")));
            tracker.record(new PatchReloadTracker.Observation(target.epoch(), target.target(), target.expectedHash(), PatchReloadTracker.Outcome.LOADED));
            return HytalePatchTargetAdapter.AdapterReply.confirmed();
        });
        PatchReloadCoordinator coordinator = new PatchReloadCoordinator(root, tracker, builtIn, List.of(), Duration.ofSeconds(1));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/Test.json", "new"))));

        assertTrue(manifestPresent.get());
        assertEquals(PatchReloadCoordinator.TargetState.HOT_RELOADED, outcome.targets().getFirst().state());
        assertArrayEquals("new".getBytes(), Files.readAllBytes(root.resolve("Server/AssetStore/Test.json")));
    }

    @Test
    void selectsRegisteredHostAdapterForCustomTargets() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated"));
        PatchReloadTracker tracker = new PatchReloadTracker(); AtomicBoolean hostCalled = new AtomicBoolean();
        HytalePatchTargetAdapter host = adapter("host", target -> { hostCalled.set(true); tracker.record(loaded(target)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        PatchReloadCoordinator coordinator = new PatchReloadCoordinator(root, tracker, unsupported(), List.of(host), Duration.ofSeconds(1));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/Tamework/Test.json", "new"))));

        assertTrue(hostCalled.get()); assertEquals(PatchReloadCoordinator.TargetState.ADAPTER_RELOADED, outcome.targets().getFirst().state());
    }

    @Test
    void confirmsAssetStoreParticleCommonAndNpcObservations() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker();
        HytalePatchTargetAdapter adapter = adapter("built-in", target -> { tracker.record(loaded(target)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        var coordinator = new PatchReloadCoordinator(root, tracker, adapter, List.of(), Duration.ofSeconds(1));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"), update("Server/Particles/B.json", "b"), update("Server/Common/C.json", "c"), update("Server/NPC/D.json", "d"))));

        assertEquals(List.of(PatchReloadCoordinator.TargetState.HOT_RELOADED, PatchReloadCoordinator.TargetState.HOT_RELOADED, PatchReloadCoordinator.TargetState.HOT_RELOADED, PatchReloadCoordinator.TargetState.HOT_RELOADED), outcome.targets().stream().map(PatchReloadCoordinator.TargetOutcome::state).toList());
    }

    @Test
    void timeoutRestoresJournaledBytesAndReportsStaleAfterRollbackConfirmation() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated/Server/AssetStore")); Path target = root.resolve("Test.json"); Files.writeString(target, "old");
        PatchReloadTracker tracker = new PatchReloadTracker(); AtomicBoolean rollback = new AtomicBoolean();
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> { if (rollback.getAndSet(true)) tracker.record(loaded(request)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        var coordinator = new PatchReloadCoordinator(temporary.resolve("generated"), tracker, adapter, List.of(), Duration.ofMillis(15));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/Test.json", "new"))));

        assertEquals(PatchReloadCoordinator.TargetState.STALE, outcome.targets().getFirst().state()); assertArrayEquals("old".getBytes(), Files.readAllBytes(target));
    }

    @Test
    void observerFailureRollsBackAndMarksRollbackFailureWhenRestoreCannotBeConfirmed() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated/Server/AssetStore")); Path target = root.resolve("Test.json"); Files.writeString(target, "old");
        PatchReloadTracker tracker = new PatchReloadTracker(); AtomicBoolean first = new AtomicBoolean(true);
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> { if (first.getAndSet(false)) tracker.record(new PatchReloadTracker.Observation(request.epoch(), request.target(), request.expectedHash(), PatchReloadTracker.Outcome.FAILED)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        var coordinator = new PatchReloadCoordinator(temporary.resolve("generated"), tracker, adapter, List.of(), Duration.ofMillis(15));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/Test.json", "new"))));

        assertEquals(PatchReloadCoordinator.TargetState.ROLLBACK_FAILED, outcome.targets().getFirst().state()); assertArrayEquals("old".getBytes(), Files.readAllBytes(target));
    }

    @Test
    void keepsEarlierConfirmedTargetsWhenALaterTargetFails() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker();
        AtomicInteger secondTargetCalls = new AtomicInteger();
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> { if (request.target().endsWith("A.json") || secondTargetCalls.incrementAndGet() > 1) tracker.record(loaded(request)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        var coordinator = new PatchReloadCoordinator(root, tracker, adapter, List.of(), Duration.ofMillis(15));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"), update("Server/AssetStore/B.json", "b"))));

        assertEquals(PatchReloadCoordinator.TargetState.HOT_RELOADED, outcome.targets().get(0).state()); assertEquals(PatchReloadCoordinator.TargetState.STALE, outcome.targets().get(1).state()); assertTrue(Files.exists(root.resolve("Server/AssetStore/A.json")));
    }

    @Test
    void restartRequiredTargetsAreWrittenWithoutInvokingAnyAdapter() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker(); AtomicBoolean invoked = new AtomicBoolean();
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> { invoked.set(true); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        var coordinator = new PatchReloadCoordinator(root, tracker, adapter, List.of(), Duration.ofSeconds(1));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/Unknown/Test.json", "new"))));

        assertFalse(invoked.get()); assertEquals(PatchReloadCoordinator.TargetState.RESTART_REQUIRED, outcome.targets().getFirst().state());
    }

    @Test
    void rejectsAllNonCommandTriggersWithoutGenerating() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker(); AtomicBoolean generated = new AtomicBoolean();
        var coordinator = new PatchReloadCoordinator(root, tracker, unsupported(), List.of(), Duration.ofSeconds(1));

        var outcome = coordinator.reload(new PatchReloadCoordinator.ReloadRequest(PatchReloadCoordinator.Trigger.SOURCE_EDIT, "patchwork.admin", () -> { generated.set(true); return plan("manifest"); }));

        assertFalse(outcome.started()); assertFalse(generated.get()); assertFalse(Files.exists(root.resolve("patchwork-manifest.json")));
    }

    @Test
    void rejectsGeneratedPackObservationsAndWrongPermissionsWithoutGenerating() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); AtomicBoolean generated = new AtomicBoolean();
        var coordinator = new PatchReloadCoordinator(root, new PatchReloadTracker(), unsupported(), List.of(), Duration.ofSeconds(1));

        assertFalse(coordinator.reload(new PatchReloadCoordinator.ReloadRequest(PatchReloadCoordinator.Trigger.GENERATED_PACK_OBSERVATION, "patchwork.admin", () -> { generated.set(true); return plan("manifest"); })).started());
        assertFalse(coordinator.reload(new PatchReloadCoordinator.ReloadRequest(PatchReloadCoordinator.Trigger.PATCHWORK_RELOAD_COMMAND, "other.admin", () -> { generated.set(true); return plan("manifest"); })).started());

        assertFalse(generated.get());
    }

    @Test
    void manifestCommitFailureLeavesExistingTargetsUntouched() throws Exception {
        Path root = temporary.resolve("generated"); Files.writeString(root, "not a directory");
        var coordinator = new PatchReloadCoordinator(root, new PatchReloadTracker(), unsupported(), List.of(), Duration.ofSeconds(1));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/Test.json", "new"))));

        assertTrue(outcome.started()); assertTrue(outcome.targets().isEmpty()); assertFalse(Files.exists(temporary.resolve("generated/Server/AssetStore/Test.json")));
    }

    @Test
    void journalPreservesOldBytesAndHashAndAtomicReplacementFallsBackOnlyWhenUnsupported() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated/Server/AssetStore")); Path target = root.resolve("Test.json"); Files.writeString(target, "old");
        AtomicBoolean atomic = new AtomicBoolean(); AtomicBoolean fallback = new AtomicBoolean();
        TargetPatchTransaction transaction = new TargetPatchTransaction(temporary.resolve("generated"), new TargetPatchTransaction.MoveStrategy() {
            @Override public void atomicMove(Path from, Path to) throws java.io.IOException { atomic.set(true); throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "test"); }
            @Override public void nonAtomicMove(Path from, Path to) throws java.io.IOException { fallback.set(true); Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
        });

        TargetJournalEntry journal = transaction.journal("Server/AssetStore/Test.json"); transaction.apply("Server/AssetStore/Test.json", "new".getBytes());

        assertArrayEquals("old".getBytes(), journal.oldBytes()); assertEquals(TargetJournalEntry.hash("old".getBytes()), journal.oldHash()); assertTrue(atomic.get()); assertTrue(fallback.get()); assertArrayEquals("new".getBytes(), Files.readAllBytes(target));
    }

    @Test
    void deletionUsesJournalAndRestoresPriorBytesAfterTimeout() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated/Server/AssetStore")); Path target = root.resolve("Test.json"); Files.writeString(target, "old");
        PatchReloadTracker tracker = new PatchReloadTracker(); AtomicBoolean rollback = new AtomicBoolean();
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> { if (rollback.getAndSet(true)) tracker.record(loaded(request)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        var coordinator = new PatchReloadCoordinator(temporary.resolve("generated"), tracker, adapter, List.of(), Duration.ofMillis(15));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> new PatchReloadCoordinator.ReloadPlan("manifest".getBytes(), List.of(new PatchReloadCoordinator.TargetUpdate("Server/AssetStore/Test.json", null)))));

        assertEquals(PatchReloadCoordinator.TargetState.STALE, outcome.targets().getFirst().state()); assertArrayEquals("old".getBytes(), Files.readAllBytes(target));
    }

    @Test
    void observerFailureWithConfirmedRestoreReportsStale() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated/Server/AssetStore")); Path target = root.resolve("Test.json"); Files.writeString(target, "old");
        PatchReloadTracker tracker = new PatchReloadTracker(); AtomicBoolean first = new AtomicBoolean(true);
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> { if (first.getAndSet(false)) tracker.record(new PatchReloadTracker.Observation(request.epoch(), request.target(), request.expectedHash(), PatchReloadTracker.Outcome.FAILED)); else tracker.record(loaded(request)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        var coordinator = new PatchReloadCoordinator(temporary.resolve("generated"), tracker, adapter, List.of(), Duration.ofMillis(15));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/Test.json", "new"))));

        assertEquals(PatchReloadCoordinator.TargetState.STALE, outcome.targets().getFirst().state()); assertArrayEquals("old".getBytes(), Files.readAllBytes(target));
    }

    @Test
    void serializesConcurrentAuthorizedPassesUntilTheCurrentPassCompletes() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker();
        CountDownLatch firstAdapterStarted = new CountDownLatch(1); CountDownLatch releaseFirst = new CountDownLatch(1); AtomicBoolean secondGenerated = new AtomicBoolean();
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> { if (request.epoch() == 1L) { firstAdapterStarted.countDown(); releaseFirst.await(1, TimeUnit.SECONDS); } tracker.record(loaded(request)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        var coordinator = new PatchReloadCoordinator(root, tracker, adapter, List.of(), Duration.ofSeconds(1));
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("first", update("Server/AssetStore/A.json", "a")))));

        assertTrue(firstAdapterStarted.await(1, TimeUnit.SECONDS));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> { secondGenerated.set(true); return plan("second", update("Server/AssetStore/B.json", "b")); })));
        Thread.sleep(25);
        assertFalse(secondGenerated.get());
        releaseFirst.countDown(); first.get(1, TimeUnit.SECONDS); second.get(1, TimeUnit.SECONDS);
        assertTrue(secondGenerated.get());
    }

    @Test
    void replacementMoveThatThrowsAfterMutationRestoresJournaledBytes() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated/Server/AssetStore")); Path target = root.resolve("Test.json"); Files.writeString(target, "old");
        PatchReloadTracker tracker = new PatchReloadTracker(); AtomicBoolean replacement = new AtomicBoolean();
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> { if (!replacement.getAndSet(true)) tracker.record(loaded(request)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        TargetPatchTransaction.MoveStrategy moves = moveThenThrow(root.resolve("Test.json"));
        var coordinator = new PatchReloadCoordinator(temporary.resolve("generated"), tracker, adapter, List.of(), Duration.ofMillis(25), moves, defaultManifestMoves());

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/Test.json", "new"))));

        assertEquals(PatchReloadCoordinator.TargetState.STALE, outcome.targets().getFirst().state()); assertArrayEquals("old".getBytes(), Files.readAllBytes(target));
    }

    @Test
    void nullAdapterReplyAfterApplyRollsBackRatherThanLeavingNewBytes() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated/Server/AssetStore")); Path target = root.resolve("Test.json"); Files.writeString(target, "old");
        PatchReloadTracker tracker = new PatchReloadTracker(); AtomicBoolean first = new AtomicBoolean(true);
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> { if (first.getAndSet(false)) return null; tracker.record(loaded(request)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        var coordinator = new PatchReloadCoordinator(temporary.resolve("generated"), tracker, adapter, List.of(), Duration.ofMillis(25));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/Test.json", "new"))));

        assertEquals(PatchReloadCoordinator.TargetState.STALE, outcome.targets().getFirst().state()); assertArrayEquals("old".getBytes(), Files.readAllBytes(target));
    }

    @Test
    void uncertainManifestMoveStopsAllTargetWritesAndReportsItsState() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); Path target = root.resolve("Server/AssetStore/Test.json");
        PatchReloadTracker tracker = new PatchReloadTracker();
        var coordinator = new PatchReloadCoordinator(root, tracker, unsupported(), List.of(), Duration.ofMillis(25), defaultTargetMoves(), moveThenThrow(root.resolve("patchwork-manifest.json")));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/Test.json", "new"))));

        assertEquals(PatchReloadCoordinator.ManifestState.COMMIT_UNCERTAIN, outcome.manifestState()); assertTrue(Files.exists(root.resolve("patchwork-manifest.json"))); assertFalse(Files.exists(target));
    }

    @Test
    void rollbackOfPreviouslyAbsentTargetRequiresRemovedConfirmation() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); Path target = root.resolve("Server/AssetStore/New.json");
        PatchReloadTracker tracker = new PatchReloadTracker(); AtomicInteger calls = new AtomicInteger();
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> { if (calls.incrementAndGet() == 2) tracker.record(loaded(request)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        var coordinator = new PatchReloadCoordinator(root, tracker, adapter, List.of(), Duration.ofMillis(15));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/New.json", "new"))));

        assertEquals(PatchReloadCoordinator.TargetState.STALE, outcome.targets().getFirst().state()); assertFalse(Files.exists(target));
    }

    @Test
    void uncertainRollbackMoveReportsRollbackFailureWithoutLeavingNewBytes() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated/Server/AssetStore")); Path target = root.resolve("Test.json"); Files.writeString(target, "old");
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> HytalePatchTargetAdapter.AdapterReply.confirmed());
        TargetPatchTransaction.MoveStrategy moves = moveAlwaysThenThrow(root.resolve("Test.json"));
        var coordinator = new PatchReloadCoordinator(temporary.resolve("generated"), new PatchReloadTracker(), adapter, List.of(), Duration.ofMillis(15), moves, defaultManifestMoves());

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/Test.json", "new"))));

        assertEquals(PatchReloadCoordinator.TargetState.ROLLBACK_FAILED, outcome.targets().getFirst().state()); assertArrayEquals("old".getBytes(), Files.readAllBytes(target));
    }

    private static PatchReloadTracker.Observation loaded(HytalePatchTargetAdapter.ReloadTarget target) { return new PatchReloadTracker.Observation(target.epoch(), target.target(), target.expectedHash(), target.removal() ? PatchReloadTracker.Outcome.REMOVED : PatchReloadTracker.Outcome.LOADED); }
    private static TargetPatchTransaction.MoveStrategy moveThenThrow(Path expectedTarget) { AtomicBoolean thrown = new AtomicBoolean(); return new TargetPatchTransaction.MoveStrategy() {
        @Override public void atomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); if (to.equals(expectedTarget) && thrown.compareAndSet(false, true)) throw new java.io.IOException("move committed then reported failure"); }
        @Override public void nonAtomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }; }
    private static TargetPatchTransaction.MoveStrategy moveAlwaysThenThrow(Path expectedTarget) { return new TargetPatchTransaction.MoveStrategy() {
        @Override public void atomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); if (to.equals(expectedTarget)) throw new java.io.IOException("move committed then reported failure"); }
        @Override public void nonAtomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }; }
    private static TargetPatchTransaction.MoveStrategy defaultTargetMoves() { return new TargetPatchTransaction.MoveStrategy() { @Override public void atomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } @Override public void nonAtomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); } }; }
    private static TargetPatchTransaction.MoveStrategy defaultManifestMoves() { return defaultTargetMoves(); }
    private static HytalePatchTargetAdapter adapter(String id, HytalePatchTargetAdapter.ReloadAction action) { return new HytalePatchTargetAdapter(id, target -> !target.target().contains("Unknown"), action); }
    private static HytalePatchTargetAdapter unsupported() { return new HytalePatchTargetAdapter("none", target -> false, target -> HytalePatchTargetAdapter.AdapterReply.confirmed()); }
    private static PatchReloadCoordinator.ReloadPlan plan(String manifest, PatchReloadCoordinator.TargetUpdate... updates) { return new PatchReloadCoordinator.ReloadPlan(manifest.getBytes(), List.of(updates)); }
    private static PatchReloadCoordinator.TargetUpdate update(String target, String contents) { return new PatchReloadCoordinator.TargetUpdate(target, contents.getBytes()); }
}
