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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

/** Exercises target-local live reload commits and recovery. */
final class PatchReloadCoordinatorTest {
    @TempDir Path temporary;

    @Test
    void authorizedReloadCommitsManifestBeforeWritingAndConfirmingBuiltInTargets() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated"));
        PatchReloadTracker tracker = new PatchReloadTracker();
        AtomicBoolean manifestPresent = new AtomicBoolean();
        HytalePatchTargetAdapter builtIn = adapter("built-in", target -> {
            manifestPresent.set(Files.exists(root.resolve("manifest.json")));
            tracker.record(new PatchReloadTracker.Observation(target.token(), target.epoch(), target.target(), target.expectedHash(), PatchReloadTracker.Outcome.LOADED));
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
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> { if (first.getAndSet(false)) tracker.record(new PatchReloadTracker.Observation(request.token(), request.epoch(), request.target(), request.expectedHash(), PatchReloadTracker.Outcome.FAILED)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        var coordinator = new PatchReloadCoordinator(temporary.resolve("generated"), tracker, adapter, List.of(), Duration.ofMillis(15));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/Test.json", "new"))));

        assertEquals(PatchReloadCoordinator.TargetState.ROLLBACK_FAILED, outcome.targets().getFirst().state()); assertArrayEquals("old".getBytes(), Files.readAllBytes(target)); assertTrue(outcome.targets().getFirst().restartRequired()); assertArrayEquals("old".getBytes(), outcome.targets().getFirst().rollbackEvidence().oldBytes()); assertTrue(Files.exists(outcome.targets().getFirst().rollbackEvidencePath().resolve("metadata.txt"))); assertArrayEquals("old".getBytes(), Files.readAllBytes(outcome.targets().getFirst().rollbackEvidencePath().resolve("old-bytes.bin")));
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
        HytalePatchTargetAdapter adapter = adapter("built-in", request -> { if (first.getAndSet(false)) tracker.record(new PatchReloadTracker.Observation(request.token(), request.epoch(), request.target(), request.expectedHash(), PatchReloadTracker.Outcome.FAILED)); else tracker.record(loaded(request)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
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
        var coordinator = new PatchReloadCoordinator(root, tracker, unsupported(), List.of(), Duration.ofMillis(25), defaultTargetMoves(), moveThenThrow(root.resolve("manifest.json")));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/Test.json", "new"))));

        assertEquals(PatchReloadCoordinator.ManifestState.COMMIT_UNCERTAIN, outcome.manifestState()); assertTrue(Files.exists(root.resolve("manifest.json"))); assertFalse(Files.exists(target));
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

    @Test
    void acceptsSynchronousObserverFiredByTheFilesystemMutation() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker();
        TargetPatchTransaction.MoveStrategy moves = new TargetPatchTransaction.MoveStrategy() {
            @Override public void atomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
            @Override public void nonAtomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
        };
        var coordinator = new PatchReloadCoordinator(root, tracker, adapter("built-in", target -> { tracker.record(loaded(target)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); }), List.of(), Duration.ofMillis(25), moves, defaultManifestMoves());

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/Test.json", "new"))));

        assertEquals(PatchReloadCoordinator.TargetState.HOT_RELOADED, outcome.targets().getFirst().state());
    }

    @Test
    void rejectsSameThreadGeneratorReentryWithoutStartingAnotherEpoch() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker();
        var coordinator = new PatchReloadCoordinator(root, tracker, adapter("built-in", target -> { tracker.record(loaded(target)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); }), List.of(), Duration.ofMillis(25));
        PatchReloadCoordinator.ReloadOutcome[] nested = new PatchReloadCoordinator.ReloadOutcome[1];

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> { nested[0] = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("nested"))); return plan("manifest", update("Server/AssetStore/Test.json", "new")); }));

        assertTrue(outcome.started()); assertFalse(nested[0].started()); assertEquals(1L, outcome.epoch());
    }

    @Test
    void adapterRejectedAndAdapterRestartRequiredDoNotClaimHotReload() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated"));
        var rejected = new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter("built-in", target -> HytalePatchTargetAdapter.AdapterReply.rejected("no")), List.of(), Duration.ofMillis(10));
        var restart = new PatchReloadCoordinator(Files.createDirectories(temporary.resolve("restart")), new PatchReloadTracker(), adapter("built-in", target -> HytalePatchTargetAdapter.AdapterReply.restartRequired("later")), List.of(), Duration.ofMillis(10));

        assertEquals(PatchReloadCoordinator.TargetState.ROLLBACK_FAILED, rejected.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a")))).targets().getFirst().state());
        assertEquals(PatchReloadCoordinator.TargetState.RESTART_REQUIRED, restart.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/B.json", "b")))).targets().getFirst().state());
    }

    @Test
    void adapterReentryAndRevocationFenceLaterTargets() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker(); PatchReloadCoordinator.ReloadOutcome[] nested = new PatchReloadCoordinator.ReloadOutcome[1];
        PatchReloadCoordinator[] holder = new PatchReloadCoordinator[1];
        HytalePatchTargetAdapter adapter = adapter("built-in", target -> { nested[0] = holder[0].reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("nested"))); holder[0].revoke(0L); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        holder[0] = new PatchReloadCoordinator(root, tracker, adapter, List.of(), Duration.ofMillis(10));

        var outcome = holder[0].reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"), update("Server/AssetStore/B.json", "b"))));

        assertFalse(nested[0].started()); assertEquals(1, outcome.targets().size()); assertFalse(Files.exists(root.resolve("Server/AssetStore/B.json")));
    }

    @Test
    void staleRevocationCannotFenceNewerActivation() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker();
        var coordinator = new PatchReloadCoordinator(root, tracker, adapter("built-in", target -> { tracker.record(loaded(target)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); }), List.of(), Duration.ofMillis(10));
        coordinator.activate(2L); coordinator.revoke(1L);

        assertTrue(coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a")))).started());
    }

    @Test
    void equalActivationCannotResurrectRevokedOwner() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); var coordinator = new PatchReloadCoordinator(root, new PatchReloadTracker(), unsupported(), List.of(), Duration.ofMillis(5));
        coordinator.activate(2L); coordinator.revoke(2L); coordinator.activate(2L);
        assertFalse(coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest"))).started());
    }

    @Test
    void refusesRootIntermediateFinalAndManifestLinksWithoutTouchingOutsideBytes() throws Exception {
        Path outside = Files.createDirectories(temporary.resolve("outside")); Path marker = outside.resolve("marker.txt"); Files.writeString(marker, "safe"); AtomicBoolean adapterCalled = new AtomicBoolean();
        HytalePatchTargetAdapter adapter = adapter("built-in", target -> { adapterCalled.set(true); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
        Path rootLink = temporary.resolve("root-link"); try { Files.createSymbolicLink(rootLink, outside); } catch (java.nio.file.FileSystemException unavailable) { return; }
        assertTrue(new PatchReloadCoordinator(rootLink, new PatchReloadTracker(), adapter, List.of(), Duration.ofMillis(5)).reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a")))).targets().isEmpty());
        Path root = Files.createDirectories(temporary.resolve("generated")); Files.createSymbolicLink(root.resolve("Server"), outside);
        new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter, List.of(), Duration.ofMillis(5)).reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"))));
        Files.delete(root.resolve("Server")); Files.createDirectories(root.resolve("Server/AssetStore")); Files.createSymbolicLink(root.resolve("Server/AssetStore/A.json"), marker);
        new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter, List.of(), Duration.ofMillis(5)).reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"))));
        Files.delete(root.resolve("Server/AssetStore/A.json")); Files.createSymbolicLink(root.resolve("manifest.json"), marker);
        var manifestOutcome = new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter, List.of(), Duration.ofMillis(5)).reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/B.json", "b"))));
        assertEquals("safe", Files.readString(marker)); assertFalse(adapterCalled.get()); assertEquals(PatchReloadCoordinator.ManifestState.UNCHANGED, manifestOutcome.manifestState());
    }

    @Test
    void drainReportsCompletionAfterRevocation() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker();
        var coordinator = new PatchReloadCoordinator(root, tracker, unsupported(), List.of(), Duration.ofMillis(5));
        coordinator.activate(3L); coordinator.revoke(3L);
        assertTrue(coordinator.drain(Duration.ofMillis(5)));
    }

    @Test
    void jimfsUnixRejectsRootIntermediateFinalAndManifestLinksWithoutWritingOutside() throws Exception {
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path outside = Files.createDirectories(fileSystem.getPath("/outside")); Path marker = outside.resolve("marker"); Files.writeString(marker, "safe"); AtomicBoolean called = new AtomicBoolean(); HytalePatchTargetAdapter adapter = adapter("built-in", request -> { called.set(true); return HytalePatchTargetAdapter.AdapterReply.confirmed(); });
            Path rootLink = fileSystem.getPath("/root-link"); Files.createSymbolicLink(rootLink, outside);
            new PatchReloadCoordinator(rootLink, new PatchReloadTracker(), adapter, List.of(), Duration.ofMillis(5)).reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"))));
            Path root = Files.createDirectories(fileSystem.getPath("/generated")); Files.createSymbolicLink(root.resolve("Server"), outside);
            new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter, List.of(), Duration.ofMillis(5)).reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"))));
            Files.delete(root.resolve("Server")); Files.createDirectories(root.resolve("Server/AssetStore")); Files.createSymbolicLink(root.resolve("Server/AssetStore/A.json"), marker);
            new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter, List.of(), Duration.ofMillis(5)).reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"))));
            Files.delete(root.resolve("Server/AssetStore/A.json")); Files.deleteIfExists(root.resolve("manifest.json")); Files.createSymbolicLink(root.resolve("manifest.json"), marker);
            new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter, List.of(), Duration.ofMillis(5)).reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/B.json", "b"))));
            assertEquals("safe", Files.readString(marker)); assertFalse(called.get());
        }
    }

    @Test
    void blockedAwaitDrainsOnlyAfterExternalRevocationAndNeverStartsLaterTarget() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker(); CountDownLatch adapterStarted = new CountDownLatch(1);
        var coordinator = new PatchReloadCoordinator(root, tracker, adapter("built-in", target -> { adapterStarted.countDown(); return HytalePatchTargetAdapter.AdapterReply.confirmed(); }), List.of(), Duration.ofSeconds(2));
        CompletableFuture<PatchReloadCoordinator.ReloadOutcome> pass = CompletableFuture.supplyAsync(() -> coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"), update("Server/AssetStore/B.json", "b")))));
        assertTrue(adapterStarted.await(1, TimeUnit.SECONDS)); assertFalse(coordinator.drain(Duration.ofMillis(5)));
        coordinator.revoke(0L); var outcome = pass.get(1, TimeUnit.SECONDS);
        assertTrue(coordinator.drain(Duration.ofMillis(100))); assertEquals(1, outcome.targets().size()); assertFalse(Files.exists(root.resolve("Server/AssetStore/B.json")));
    }

    @Test
    void revokeCancelsPromptlyWhileAnAdapterIsBlocked() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker(); CountDownLatch adapterStarted = new CountDownLatch(1); CountDownLatch unblockAdapter = new CountDownLatch(1);
        var coordinator = new PatchReloadCoordinator(root, tracker, adapter("built-in", target -> { adapterStarted.countDown(); assertTrue(unblockAdapter.await(1, TimeUnit.SECONDS)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); }), List.of(), Duration.ofSeconds(2));
        CompletableFuture<PatchReloadCoordinator.ReloadOutcome> pass = CompletableFuture.supplyAsync(() -> coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a")))));

        CountDownLatch revocationReturned = new CountDownLatch(1); assertTrue(adapterStarted.await(1, TimeUnit.SECONDS)); CompletableFuture.runAsync(() -> { coordinator.revoke(0L); revocationReturned.countDown(); }); assertTrue(revocationReturned.await(100, TimeUnit.MILLISECONDS)); assertFalse(coordinator.drain(Duration.ofMillis(10)));
        unblockAdapter.countDown(); assertTrue(pass.get(10, TimeUnit.SECONDS).started()); assertTrue(coordinator.drain(Duration.ofMillis(100)));
    }

    @Test
    void evidencePersistenceDoesNotHoldLifecycleLockAfterRollbackRevocation() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("evidence-lock")); Path target = root.resolve("Server/AssetStore/A.json"); Files.createDirectories(target.getParent()); Files.writeString(target, "old");
        CountDownLatch evidenceWriterEntered = new CountDownLatch(1); CountDownLatch releaseEvidenceWriter = new CountDownLatch(1);
        PatchReloadCoordinator[] holder = new PatchReloadCoordinator[1]; AtomicInteger mutations = new AtomicInteger();
        TargetPatchTransaction.MoveStrategy moves = new TargetPatchTransaction.MoveStrategy() {
            @Override public void beforeMutation(Path ignored) { if (mutations.incrementAndGet() == 2) holder[0].revoke(0L); }
            @Override public void atomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
            @Override public void nonAtomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
        };
        holder[0] = new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter("built-in", ignored -> HytalePatchTargetAdapter.AdapterReply.rejected("no")), List.of(), Duration.ofMillis(10), moves, defaultManifestMoves(), () -> {
            evidenceWriterEntered.countDown();
            try { releaseEvidenceWriter.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        });
        CompletableFuture<PatchReloadCoordinator.ReloadOutcome> pass = CompletableFuture.supplyAsync(() -> holder[0].reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "new")))));

        try {
            assertTrue(evidenceWriterEntered.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> activate = CompletableFuture.runAsync(() -> holder[0].activate(1L));
            activate.get(100, TimeUnit.MILLISECONDS);
            assertFalse(CompletableFuture.supplyAsync(() -> holder[0].drain(Duration.ofMillis(5))).get(100, TimeUnit.MILLISECONDS));
        } finally { releaseEvidenceWriter.countDown(); }

        PatchReloadCoordinator.TargetOutcome outcome = pass.get(1, TimeUnit.SECONDS).targets().getFirst();
        assertEquals(PatchReloadCoordinator.TargetState.ROLLBACK_FAILED, outcome.state()); assertTrue(Files.exists(outcome.rollbackEvidencePath().resolve("metadata.txt"))); assertTrue(holder[0].drain(Duration.ofSeconds(1)));
    }

    @Test
    void admissionIsVisibleToDrainBeforeGeneratorWork() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("admission")); CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        var coordinator = new PatchReloadCoordinator(root, new PatchReloadTracker(), unsupported(), List.of(), Duration.ofMillis(10));
        CompletableFuture<PatchReloadCoordinator.ReloadOutcome> pass = CompletableFuture.supplyAsync(() -> coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } return plan("manifest"); })));
        assertTrue(entered.await(1, TimeUnit.SECONDS)); assertFalse(coordinator.drain(Duration.ofMillis(5))); coordinator.revoke(0); release.countDown(); assertTrue(pass.get(1, TimeUnit.SECONDS).started()); assertTrue(coordinator.drain(Duration.ofSeconds(1)));
    }

    @Test
    void blockingHostSupportDoesNotDelayRevoke() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("supports")); CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1); PatchReloadTracker tracker = new PatchReloadTracker();
        HytalePatchTargetAdapter host = new HytalePatchTargetAdapter("host", target -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } return true; }, target -> HytalePatchTargetAdapter.AdapterReply.confirmed());
        var coordinator = new PatchReloadCoordinator(root, tracker, unsupported(), List.of(host), Duration.ofMillis(20));
        CompletableFuture<PatchReloadCoordinator.ReloadOutcome> pass = CompletableFuture.supplyAsync(() -> coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Custom/A.json", "a")))));
        assertTrue(entered.await(1, TimeUnit.SECONDS)); CountDownLatch returned = new CountDownLatch(1); CompletableFuture.runAsync(() -> { coordinator.revoke(0); returned.countDown(); }); assertTrue(returned.await(100, TimeUnit.MILLISECONDS)); release.countDown(); assertTrue(pass.get(1, TimeUnit.SECONDS).started()); assertTrue(coordinator.drain(Duration.ofSeconds(1)));
    }

    @Test
    void authorizedAdapterMayDrainAfterRevokeWithoutStartingNextTarget() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("adapter-drain")); CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        var coordinator = new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter("built-in", target -> { entered.countDown(); assertTrue(release.await(1, TimeUnit.SECONDS)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); }), List.of(), Duration.ofMillis(20));
        CompletableFuture<PatchReloadCoordinator.ReloadOutcome> pass = CompletableFuture.supplyAsync(() -> coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"), update("Server/AssetStore/B.json", "b")))));
        assertTrue(entered.await(1, TimeUnit.SECONDS)); coordinator.revoke(0); release.countDown(); PatchReloadCoordinator.ReloadOutcome result = pass.get(10, TimeUnit.SECONDS);
        assertEquals(1, result.targets().size()); assertFalse(Files.exists(root.resolve("Server/AssetStore/B.json"))); assertTrue(coordinator.drain(Duration.ofSeconds(1)));
    }

    @Test
    void revokedMutationScopeRestoresOldBytesWithoutRollbackAdapterOrLaterTarget() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("mutation-cleanup")); Path target = root.resolve("Server/AssetStore/A.json"); Files.createDirectories(target.getParent()); Files.writeString(target, "old"); PatchReloadCoordinator[] holder = new PatchReloadCoordinator[1]; AtomicInteger adapterCalls = new AtomicInteger();
        TargetPatchTransaction.MoveStrategy moves = new TargetPatchTransaction.MoveStrategy() {
            @Override public void beforeMutation(Path ignored) { holder[0].revoke(0); }
            @Override public void atomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
            @Override public void nonAtomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
        };
        holder[0] = new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter("built-in", ignored -> { adapterCalls.incrementAndGet(); return HytalePatchTargetAdapter.AdapterReply.confirmed(); }), List.of(), Duration.ofMillis(20), moves, defaultManifestMoves());
        var result = holder[0].reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "new"), update("Server/AssetStore/B.json", "b"))));
        assertEquals(PatchReloadCoordinator.TargetState.ROLLBACK_FAILED, result.targets().getFirst().state()); assertEquals("old", Files.readString(target)); assertEquals(0, adapterCalls.get()); assertFalse(Files.exists(root.resolve("Server/AssetStore/B.json")));
    }

    @Test
    void newerActivationDoesNotResurrectRevokedPassWhileItsAdapterDrains() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("old-pass")); CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        var coordinator = new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter("built-in", target -> { entered.countDown(); assertTrue(release.await(1, TimeUnit.SECONDS)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); }), List.of(), Duration.ofMillis(20));
        CompletableFuture<PatchReloadCoordinator.ReloadOutcome> pass = CompletableFuture.supplyAsync(() -> coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"), update("Server/AssetStore/B.json", "b")))));
        assertTrue(entered.await(1, TimeUnit.SECONDS)); coordinator.revoke(0); coordinator.activate(1); release.countDown(); PatchReloadCoordinator.ReloadOutcome result = pass.get(1, TimeUnit.SECONDS);
        assertEquals(1, result.targets().size()); assertFalse(Files.exists(root.resolve("Server/AssetStore/B.json"))); assertFalse(Files.exists(root.resolve("patchwork-manifest.json"))); assertTrue(coordinator.drain(Duration.ofSeconds(1)));
    }

    @Test
    void revokeAfterSupportBeforeReservationPreventsWrite() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("reservation")); PatchReloadCoordinator[] holder = new PatchReloadCoordinator[1];
        HytalePatchTargetAdapter host = new HytalePatchTargetAdapter("host", target -> { holder[0].revoke(0); return true; }, target -> HytalePatchTargetAdapter.AdapterReply.confirmed());
        holder[0] = new PatchReloadCoordinator(root, new PatchReloadTracker(), unsupported(), List.of(host), Duration.ofMillis(20));
        holder[0].reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Custom/A.json", "a"))));
        assertFalse(Files.exists(root.resolve("Custom/A.json")));
    }

    @Test
    void longTargetRollbackEvidenceUsesBoundedDirectoryAndPreservesBytes() throws Exception {
        String target = "Server/AssetStore/" + String.join("/", java.util.Collections.nCopies(30, "segment123")) + "/A.json"; Path root = Files.createDirectories(temporary.resolve("long")); Path original = root.resolve(target); Files.createDirectories(original.getParent()); Files.writeString(original, "old");
        var coordinator = new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter("built-in", t -> HytalePatchTargetAdapter.AdapterReply.rejected("no")), List.of(), Duration.ofMillis(5));
        var result = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update(target, "new")))).targets().getFirst();
        assertEquals(PatchReloadCoordinator.TargetState.ROLLBACK_FAILED, result.state()); assertTrue(Files.exists(result.rollbackEvidencePath())); assertTrue(result.rollbackEvidencePath().getFileName().toString().length() < 240); assertEquals("old", Files.readString(result.rollbackEvidencePath().resolve("old-bytes.bin"))); assertTrue(Files.readString(result.rollbackEvidencePath().resolve("metadata.txt")).contains(target));
    }

    @Test
    void preMutationParentReplacementIsDetectedBeforeOutsideWrite() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated/Server/AssetStore")); Path outside = Files.createDirectories(temporary.resolve("outside")); Path marker = outside.resolve("marker"); Files.writeString(marker, "safe"); AtomicBoolean adapterCalled = new AtomicBoolean();
        TargetPatchTransaction.MoveStrategy moves = new TargetPatchTransaction.MoveStrategy() {
            @Override public void beforeMutation(Path target) throws java.io.IOException { Files.move(root, outside.resolve("AssetStore")); Files.createDirectories(root); }
            @Override public void atomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
            @Override public void nonAtomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
        };
        var coordinator = new PatchReloadCoordinator(temporary.resolve("generated"), new PatchReloadTracker(), adapter("built-in", target -> { adapterCalled.set(true); return HytalePatchTargetAdapter.AdapterReply.confirmed(); }), List.of(), Duration.ofMillis(10), moves, defaultManifestMoves());
        coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "new"))));
        assertEquals("safe", Files.readString(marker)); assertFalse(Files.exists(outside.resolve("AssetStore/A.json")));
    }

    @Test
    void deletionParentReplacementLeavesOutsideFileUntouched() throws Exception {
        Path generated = Files.createDirectories(temporary.resolve("generated/Server/AssetStore")); Path target = generated.resolve("A.json"); Files.writeString(target, "old"); Path outside = Files.createDirectories(temporary.resolve("outside")); Path marker = outside.resolve("marker"); Files.writeString(marker, "safe");
        TargetPatchTransaction.MoveStrategy moves = new TargetPatchTransaction.MoveStrategy() { @Override public void beforeMutation(Path ignored) throws java.io.IOException { Files.move(generated, outside.resolve("AssetStore")); Files.createDirectories(generated); } @Override public void atomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to); } @Override public void nonAtomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to); } };
        new PatchReloadCoordinator(temporary.resolve("generated"), new PatchReloadTracker(), unsupported(), List.of(), Duration.ofMillis(5), moves, defaultManifestMoves()).reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> new PatchReloadCoordinator.ReloadPlan("manifest".getBytes(), List.of(new PatchReloadCoordinator.TargetUpdate("Server/AssetStore/A.json", null)))));
        assertEquals("safe", Files.readString(marker)); assertEquals("old", Files.readString(outside.resolve("AssetStore/A.json")));
    }

    @Test
    void manifestParentReplacementLeavesOutsideManifestUntouchedAndStartsNoTarget() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); Path outside = Files.createDirectories(temporary.resolve("outside")); Path marker = outside.resolve("manifest.json"); Files.writeString(marker, "safe"); AtomicBoolean called = new AtomicBoolean();
        TargetPatchTransaction.MoveStrategy manifestMoves = new TargetPatchTransaction.MoveStrategy() { @Override public void beforeMutation(Path ignored) throws java.io.IOException { Files.move(root, outside.resolve("generated")); Files.createDirectories(root); } @Override public void atomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to); } @Override public void nonAtomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to); } };
        var outcome = new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter("built-in", t -> { called.set(true); return HytalePatchTargetAdapter.AdapterReply.confirmed(); }), List.of(), Duration.ofMillis(5), defaultTargetMoves(), manifestMoves).reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"))));
        assertEquals("safe", Files.readString(marker)); assertFalse(called.get()); assertTrue(outcome.targets().isEmpty());
    }

    @Test
    void manifestRootReplacementThatRecreatesTemporaryFileFailsIdentityGuard() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); Path outside = Files.createDirectories(temporary.resolve("outside")); AtomicBoolean adapterCalled = new AtomicBoolean();
        TargetPatchTransaction.MoveStrategy manifestMoves = new TargetPatchTransaction.MoveStrategy() { @Override public void beforeMutation(Path ignored) throws java.io.IOException { Path moved = outside.resolve("generated"); Files.move(root, moved); Files.createDirectories(root); try (var files = Files.list(moved)) { Path temporaryFile = files.filter(path -> path.getFileName().toString().startsWith(".patchwork-manifest-")).findFirst().orElseThrow(); Files.copy(temporaryFile, root.resolve(temporaryFile.getFileName())); } } @Override public void atomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); } @Override public void nonAtomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); } };
        var outcome = new PatchReloadCoordinator(root, new PatchReloadTracker(), adapter("built-in", t -> { adapterCalled.set(true); return HytalePatchTargetAdapter.AdapterReply.confirmed(); }), List.of(), Duration.ofMillis(5), defaultTargetMoves(), manifestMoves).reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"))));
        assertEquals(PatchReloadCoordinator.ManifestState.UNCHANGED, outcome.manifestState()); assertFalse(adapterCalled.get()); assertFalse(Files.exists(root.resolve("Server/AssetStore/A.json")));
    }

    @Test
    void inventoryReconciliationIsUncertainWhenANewFileAppearsAfterCommit() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated")); PatchReloadTracker tracker = new PatchReloadTracker();
        TargetPatchTransaction.MoveStrategy manifestMoves = new TargetPatchTransaction.MoveStrategy() {
            @Override public void atomicMove(Path from, Path to) throws java.io.IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); if (to.getFileName().toString().equals("patchwork-manifest.json")) Files.writeString(root.resolve("Server/AssetStore/late.json"), "late"); }
            @Override public void nonAtomicMove(Path from, Path to) throws java.io.IOException { atomicMove(from, to); }
        };
        var coordinator = new PatchReloadCoordinator(root, tracker, adapter("built-in", target -> { tracker.record(loaded(target)); return HytalePatchTargetAdapter.AdapterReply.confirmed(); }), List.of(), Duration.ofMillis(20), defaultTargetMoves(), manifestMoves);

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "a"))));

        assertEquals(PatchReloadCoordinator.IntegrityState.UNCERTAIN, outcome.integrityState());
    }

    @Test
    void lateObservationAfterRollbackFailureIsRejected() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated/Server/AssetStore")); Files.writeString(root.resolve("A.json"), "old"); PatchReloadTracker tracker = new PatchReloadTracker();
        var coordinator = new PatchReloadCoordinator(temporary.resolve("generated"), tracker, adapter("built-in", t -> HytalePatchTargetAdapter.AdapterReply.rejected("no")), List.of(), Duration.ofMillis(5));
        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "new"))));
        assertEquals(PatchReloadCoordinator.TargetState.ROLLBACK_FAILED, outcome.targets().getFirst().state()); assertFalse(tracker.record(new PatchReloadTracker.Observation("late-token", outcome.epoch(), "Server/AssetStore/A.json", TargetJournalEntry.hash("old".getBytes()), PatchReloadTracker.Outcome.LOADED)));
    }

    @Test
    void rollbackUsesTheActivePassTokenInsteadOfALegacyEpochToken() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated/Server/AssetStore")); Files.writeString(root.resolve("A.json"), "old");
        PatchReloadTracker tracker = new PatchReloadTracker(); List<String> tokens = new ArrayList<>(); AtomicBoolean first = new AtomicBoolean(true);
        var coordinator = new PatchReloadCoordinator(temporary.resolve("generated"), tracker, adapter("built-in", target -> {
            tokens.add(target.token());
            if (first.getAndSet(false)) tracker.record(new PatchReloadTracker.Observation(target.token(), target.epoch(), target.target(), target.expectedHash(), PatchReloadTracker.Outcome.FAILED));
            else tracker.record(loaded(target));
            return HytalePatchTargetAdapter.AdapterReply.confirmed();
        }), List.of(), Duration.ofMillis(20));

        var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> plan("manifest", update("Server/AssetStore/A.json", "new"))));

        assertEquals(PatchReloadCoordinator.TargetState.STALE, outcome.targets().getFirst().state()); assertEquals(2, tokens.size()); assertEquals(tokens.getFirst(), tokens.getLast()); assertFalse(tokens.getLast().startsWith("legacy:"));
    }

    private static PatchReloadTracker.Observation loaded(HytalePatchTargetAdapter.ReloadTarget target) { return new PatchReloadTracker.Observation(target.token(), target.epoch(), target.target(), target.expectedHash(), target.removal() ? PatchReloadTracker.Outcome.REMOVED : PatchReloadTracker.Outcome.LOADED); }
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
