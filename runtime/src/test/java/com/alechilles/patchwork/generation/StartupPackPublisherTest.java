package com.alechilles.patchwork.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests fail-closed publication and recovery paths. */
final class StartupPackPublisherTest {
    @TempDir Path temporary;

    @Test
    void publishesFreshStagingAfterManifestAndQuarantinesPriorRoot() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        Files.createDirectories(layout.generatedRoot());
        Files.writeString(layout.generatedRoot().resolve("stale.json"), "stale");
        AtomicBoolean registered = new AtomicBoolean();
        StartupPackPublisher publisher = new StartupPackPublisher(layout, id -> registered.set(true));

        StartupPackPublisher.Publication publication = publisher.publish(plan());

        assertTrue(publication.published());
        assertTrue(registered.get());
        assertTrue(Files.exists(layout.generatedRoot().resolve("Server/Test.json")));
        assertTrue(Files.exists(layout.generatedRoot().resolve(GeneratedPackManifest.FILE_NAME)));
        assertTrue(Files.exists(layout.generatedRoot().resolve("manifest.json")));
        assertFalse(Files.exists(layout.generatedRoot().resolve("stale.json")));
        try (var entries = Files.list(layout.diagnosticsRoot())) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void registrarObservesLivePackAndBothManifestsOnlyAfterActivation() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        StartupPackPublisher publisher = new StartupPackPublisher(layout, id -> {
            assertEquals(StartupPackPublisher.PACK_ID, id);
            assertTrue(Files.exists(layout.generatedRoot().resolve("Server/Test.json")));
            assertTrue(Files.exists(layout.generatedRoot().resolve("manifest.json")));
            assertTrue(Files.exists(layout.generatedRoot().resolve(GeneratedPackManifest.FILE_NAME)));
        });
        var publication = publisher.publish(plan());
        assertTrue(publication.published(), publication.diagnostic());
    }

    @Test
    void writesDeterministicHytaleDependenciesForSnapshottedSourcePacks() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        StartupPackPublisher publisher = new StartupPackPublisher(layout, id -> { });
        GeneratedPackManifest manifest = new GeneratedPackManifest(List.of(new GeneratedPackManifest.Entry("Server/Test.json", "{}".getBytes())));
        var plan = new PatchGenerationService.GenerationPlan(manifest.entries(), new PatchStatusSnapshot(List.of(), java.util.Map.of(), List.of()), manifest,
                List.of("Zulu:Pack", StartupPackPublisher.PACK_ID, "Alpha:Pack"));

        assertTrue(publisher.publish(plan).published());
        var root = JsonParser.parseString(Files.readString(layout.generatedRoot().resolve("manifest.json"))).getAsJsonObject();
        assertEquals("Alechilles", root.get("Group").getAsString());
        assertEquals("Patchwork_GeneratedPatches", root.get("Name").getAsString());
        assertEquals("1.0.0", root.get("Version").getAsString());
        assertEquals("*", root.get("ServerVersion").getAsString());
        assertEquals(List.of("Alpha:Pack", "Zulu:Pack"), root.getAsJsonObject("Dependencies").keySet().stream().toList());
    }

    @Test
    void invalidSourceDependencyIdFailsBeforeActivationOrRegistration() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); AtomicBoolean registered = new AtomicBoolean();
        GeneratedPackManifest manifest = new GeneratedPackManifest(List.of(new GeneratedPackManifest.Entry("Server/Test.json", "{}".getBytes())));
        var plan = new PatchGenerationService.GenerationPlan(manifest.entries(), new PatchStatusSnapshot(List.of(), java.util.Map.of(), List.of()), manifest, List.of("not-a-plugin-id"));
        assertFalse(new StartupPackPublisher(layout, id -> registered.set(true)).publish(plan).published());
        assertFalse(registered.get()); assertFalse(Files.exists(layout.generatedRoot()));
    }

    @Test
    void verifierRejectsMissingWrongAndExtraHytaleDependencies() throws Exception {
        var plan = planWithSources(List.of("Alpha:Pack")); Path staging = staged(plan);
        Files.writeString(staging.resolve("manifest.json"), "{\"Group\":\"Alechilles\",\"Name\":\"Patchwork_GeneratedPatches\",\"Version\":\"1.0.0\",\"ServerVersion\":\"*\",\"Dependencies\":{\"Extra:Pack\":\"*\"}}");
        assertThrows(IOException.class, () -> StartupPackPublisher.verifyDefault(staging, plan));
    }

    @Test
    void verifierRejectsDuplicatedInventoryWithOmittedTarget() throws Exception {
        var plan = planWith("Server/Second.json", "second"); Path staging = staged(plan);
        Files.writeString(staging.resolve(GeneratedPackManifest.FILE_NAME), "{\"Files\":[{\"Target\":\"Server/Second.json\",\"Length\":6,\"Sha256\":\"bad\"},{\"Target\":\"Server/Second.json\",\"Length\":6,\"Sha256\":\"bad\"}]}");
        assertThrows(IOException.class, () -> StartupPackPublisher.verifyDefault(staging, plan));
    }

    @Test
    void verifierAcceptsExactDeterministicManifestContract() throws Exception {
        var plan = planWithSources(List.of("Alpha:Pack")); StartupPackPublisher.verifyDefault(staged(plan), plan);
    }

    @Test
    void attemptsAtomicMoveAndOnlyFallsBackForAtomicUnsupported() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        AtomicBoolean atomic = new AtomicBoolean(); AtomicBoolean fallback = new AtomicBoolean();
        StartupPackPublisher.MoveStrategy moves = new StartupPackPublisher.MoveStrategy() {
            @Override public void atomicMove(Path from, Path to) throws IOException { atomic.set(true); throw new AtomicMoveNotSupportedException("from", "to", "test"); }
            @Override public void nonAtomicMove(Path from, Path to) throws IOException { fallback.set(true); Files.move(from, to); }
        };
        StartupPackPublisher publisher = new StartupPackPublisher(layout, id -> { }, moves, (staging, plan) -> { });
        assertTrue(publisher.publish(plan()).published());
        assertTrue(atomic.get()); assertTrue(fallback.get());
    }

    @Test
    void ordinaryMoveFailureDoesNotAttemptFallbackOrRegister() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        AtomicBoolean fallback = new AtomicBoolean(); AtomicBoolean registered = new AtomicBoolean();
        StartupPackPublisher.MoveStrategy moves = new StartupPackPublisher.MoveStrategy() {
            @Override public void atomicMove(Path from, Path to) throws IOException { throw new IOException("disk failure"); }
            @Override public void nonAtomicMove(Path from, Path to) { fallback.set(true); }
        };
        StartupPackPublisher publisher = new StartupPackPublisher(layout, id -> registered.set(true), moves, (staging, plan) -> { });
        assertFalse(publisher.publish(plan()).published());
        assertFalse(fallback.get()); assertFalse(registered.get());
    }

    @Test
    void corruptionDetectedBeforeActivationNeverRegisters() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); AtomicBoolean registered = new AtomicBoolean();
        StartupPackPublisher publisher = new StartupPackPublisher(layout, id -> registered.set(true), new StartupPackPublisher.FileMoveStrategy(),
                (staging, plan) -> { throw new IOException("corrupt staged bytes"); });
        assertFalse(publisher.publish(plan()).published());
        assertFalse(Files.exists(layout.generatedRoot())); assertFalse(registered.get());
    }

    @Test
    void preActivationFailureRetainsExistingLiveAsPriorEvidence() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        Files.createDirectories(layout.generatedRoot()); Files.writeString(layout.generatedRoot().resolve("old.json"), "old");
        var publisher = new StartupPackPublisher(layout, id -> { }, new StartupPackPublisher.FileMoveStrategy(), (staging, current) -> { throw new IOException("verify failed"); });
        var publication = publisher.publish(plan());
        assertFalse(publication.published());
        Path prior = publication.recoveryEvidence().stream().filter(path -> path.getFileName().toString().startsWith("GeneratedPatches-prior-")).findFirst().orElseThrow();
        assertEquals("old", Files.readString(prior.resolve("old.json")));
    }

    @Test
    void committedThrowingPriorQuarantineIsRecordedAsPriorEvidence() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); Files.createDirectories(layout.generatedRoot()); Files.writeString(layout.generatedRoot().resolve("old.json"), "old");
        var publication = new StartupPackPublisher(layout, id -> { throw new AssertionError("must not register"); }, throwAfterMove("GeneratedPatches-prior-"), (s, p) -> { }).publish(plan());
        Path prior = publication.recoveryEvidence().stream().filter(path -> path.getFileName().toString().startsWith("GeneratedPatches-prior-")).findFirst().orElseThrow();
        assertEquals("old", Files.readString(prior.resolve("old.json"))); assertFalse(Files.exists(layout.generatedRoot()));
    }

    @Test
    void committedThrowingActivationIsNotRegisteredOrCurrent() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); AtomicBoolean registered = new AtomicBoolean();
        var publication = new StartupPackPublisher(layout, id -> registered.set(true), throwAfterMoveExact(layout.generatedRoot()), (s, p) -> { }).publish(plan());
        assertFalse(registered.get()); assertFalse(publication.published()); assertEquals(null, publication.activeRoot());
        assertEquals(1, publication.recoveryEvidence().size()); assertTrue(publication.recoveryEvidence().getFirst().getFileName().toString().startsWith("GeneratedPatches-failed-new-"));
        assertTrue(Files.exists(publication.recoveryEvidence().getFirst())); assertTrue(publication.residualEvidence().isEmpty());
    }

    @Test
    void preMutationActivationThrowThenRecoveryLeavesNoStaleResidual() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        AtomicBoolean first = new AtomicBoolean(true);
        StartupPackPublisher.MoveStrategy moves = new StartupPackPublisher.MoveStrategy() { public void atomicMove(Path from, Path to) throws IOException { move(from, to); } public void nonAtomicMove(Path from, Path to) throws IOException { move(from, to); } private void move(Path from, Path to) throws IOException { if (to.equals(layout.generatedRoot()) && first.getAndSet(false)) throw new IOException("before mutation"); Files.move(from, to); } };
        var publication = new StartupPackPublisher(layout, id -> { }, moves, (s, p) -> { }).publish(plan());
        assertFalse(publication.published()); assertTrue(publication.residualEvidence().isEmpty()); assertEquals(1, publication.recoveryEvidence().size()); assertTrue(publication.recoveryEvidence().getFirst().getFileName().toString().startsWith("GeneratedPatches-failed-new-"));
    }

    @Test
    void committedThrowingActivationWithPriorRetainsPriorAndFailedNewFinalEvidence() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); Files.createDirectories(layout.generatedRoot()); Files.writeString(layout.generatedRoot().resolve("old.json"), "old");
        var publication = new StartupPackPublisher(layout, id -> { }, throwAfterMoveExact(layout.generatedRoot()), (s, p) -> { }).publish(plan());
        Path prior = publication.recoveryEvidence().stream().filter(path -> path.getFileName().toString().startsWith("GeneratedPatches-prior-")).findFirst().orElseThrow();
        Path failed = publication.recoveryEvidence().stream().filter(path -> path.getFileName().toString().startsWith("GeneratedPatches-failed-new-")).findFirst().orElseThrow();
        assertEquals("old", Files.readString(prior.resolve("old.json"))); assertTrue(Files.exists(failed.resolve("Server/Test.json")));
        assertTrue(publication.recoveryEvidence().stream().allMatch(Files::exists)); assertTrue(publication.residualEvidence().stream().allMatch(Files::exists));
    }

    @Test
    void committedThrowingFailedNewRecoveryIsRecordedAsEvidence() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        var publication = new StartupPackPublisher(layout, attemptRegistrar(() -> { throw new IOException("registration failure"); }, () -> { }), throwAfterMove("GeneratedPatches-failed-new-"), (s, p) -> { }).publish(plan());
        assertTrue(publication.recoveryEvidence().stream().anyMatch(path -> path.getFileName().toString().startsWith("GeneratedPatches-failed-new-")));
    }

    @Test
    void partialRegistrationCommitRollsBackBeforeFailedNewRecovery() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); AtomicBoolean registered = new AtomicBoolean();
        StartupPackPublisher.PackRegistrar registrar = attemptRegistrar(() -> { registered.set(true); throw new IOException("commit"); }, () -> registered.set(false));
        var publication = new StartupPackPublisher(layout, registrar).publish(plan());
        assertFalse(publication.published()); assertFalse(publication.registrationUnresolved()); assertFalse(registered.get()); assertFalse(Files.exists(layout.generatedRoot()));
    }

    @Test
    void rollbackFailureLeavesLiveAsExplicitUnresolvedResidual() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); AtomicBoolean registered = new AtomicBoolean();
        StartupPackPublisher.PackRegistrar registrar = attemptRegistrar(() -> { registered.set(true); throw new IOException("commit"); }, () -> { throw new IOException("rollback"); });
        var publication = new StartupPackPublisher(layout, registrar).publish(plan());
        assertFalse(publication.published()); assertTrue(publication.registrationUnresolved()); assertTrue(registered.get()); assertTrue(publication.residualEvidence().contains(layout.generatedRoot()));
    }

    @Test
    void oneWayRegistrarMutationThenThrowIsExplicitlyUnresolved() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); AtomicBoolean registered = new AtomicBoolean();
        var publication = new StartupPackPublisher(layout, id -> { registered.set(true); throw new IOException("one-way partial"); }).publish(plan());
        assertFalse(publication.published()); assertTrue(registered.get()); assertTrue(publication.registrationUnresolved());
        assertTrue(publication.residualEvidence().contains(layout.generatedRoot()));
    }

    @Test
    void normalPreparedCommitPublishesWithoutRollback() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); AtomicBoolean committed = new AtomicBoolean(); AtomicBoolean rolledBack = new AtomicBoolean();
        var publication = new StartupPackPublisher(layout, attemptRegistrar(() -> committed.set(true), () -> rolledBack.set(true))).publish(plan());
        assertTrue(publication.published()); assertTrue(committed.get()); assertFalse(rolledBack.get());
    }

    @Test
    void prepareFailureRecoversFailedNewWithoutRollbackAttempt() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); AtomicBoolean rollback = new AtomicBoolean();
        StartupPackPublisher.PackRegistrar registrar = new StartupPackPublisher.PackRegistrar() { public void register(String id) { } public StartupPackPublisher.RegistrationAttempt prepare(String id) { throw new IllegalStateException("prepare"); } };
        var publication = new StartupPackPublisher(layout, registrar).publish(plan());
        assertFalse(publication.published()); assertFalse(publication.registrationUnresolved()); assertFalse(rollback.get()); assertFalse(Files.exists(layout.generatedRoot()));
    }

    @Test
    void cleansOnlyExactInterruptedStagingTreeWithNestedRegularFiles() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        Path owned = layout.dataRoot().resolve(".GeneratedPatches-staging-00000000-0000-0000-0000-000000000001");
        Files.createDirectories(owned.resolve("nested")); Files.writeString(owned.resolve("nested/partial.json"), "partial");
        Path prefixOnly = layout.dataRoot().resolve(".GeneratedPatches-staging-");
        Path similar = layout.dataRoot().resolve(".GeneratedPatches-staging-not-a-uuid");
        Path nested = layout.dataRoot().resolve("ordinary/.GeneratedPatches-staging-00000000-0000-0000-0000-000000000002");
        Path outsideSibling = temporary.resolve(".GeneratedPatches-staging-00000000-0000-0000-0000-000000000005");
        Files.createDirectories(prefixOnly); Files.createDirectories(similar); Files.createDirectories(nested); Files.createDirectories(outsideSibling);

        assertTrue(new StartupPackPublisher(layout, id -> { }).publish(plan()).published());
        assertFalse(Files.exists(owned));
        assertTrue(Files.exists(prefixOnly)); assertTrue(Files.exists(similar)); assertTrue(Files.exists(nested)); assertTrue(Files.exists(outsideSibling));
    }

    @Test
    void interruptedCleanupRejectsNonCanonicalUuidLookalikes() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        Path shortened = layout.dataRoot().resolve(".GeneratedPatches-staging-1-1-1-1-1");
        Path uppercase = layout.dataRoot().resolve(".GeneratedPatches-staging-00000000-0000-0000-0000-00000000000A");
        Path trailing = layout.dataRoot().resolve(".GeneratedPatches-staging-00000000-0000-0000-0000-00000000000a-");
        Files.createDirectories(shortened); Files.createDirectories(uppercase); Files.createDirectories(trailing);
        assertTrue(new StartupPackPublisher(layout, id -> { }).publish(plan()).published());
        assertTrue(Files.exists(shortened)); assertTrue(Files.exists(uppercase)); assertTrue(Files.exists(trailing));
    }

    @Test
    void mutationGuardRejectsIntermediateSwapBeforeStagingWrite() throws Exception {
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path server = fileSystem.getPath("/server"); Path outside = Files.createDirectories(fileSystem.getPath("/outside"));
            Files.writeString(outside.resolve("marker.txt"), "untouched");
            GeneratedPackLayout layout = new GeneratedPackLayout(server); Files.createDirectories(layout.dataRoot());
            AtomicBoolean swapped = new AtomicBoolean();
            OwnedPathAccess access = new OwnedPathAccess(layout, path -> {
                if (!swapped.get() && path.getFileName().toString().startsWith(".GeneratedPatches-staging-")) {
                    swapped.set(true); Path parked = fileSystem.getPath("/parked"); Files.move(layout.dataRoot(), parked); Files.createSymbolicLink(layout.dataRoot(), outside);
                }
            });
            var publisher = new StartupPackPublisher(layout, id -> { throw new AssertionError("must not register"); }, new StartupPackPublisher.FileMoveStrategy(), (staging, current) -> { }, Files::delete, access);
            assertFalse(publisher.publish(plan()).published());
            assertEquals("untouched", Files.readString(outside.resolve("marker.txt")));
            assertFalse(Files.exists(outside.resolve("Server/Test.json")));
        }
    }

    @Test
    void postCreateSwapFailsBeforeAnyGeneratedBytesAreWritten() throws Exception {
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path server = fileSystem.getPath("/server"); Path outside = Files.createDirectories(fileSystem.getPath("/outside")); Files.writeString(outside.resolve("marker.txt"), "untouched");
            GeneratedPackLayout layout = new GeneratedPackLayout(server); AtomicBoolean registered = new AtomicBoolean();
            OwnedPathAccess access = new OwnedPathAccess(layout, new OwnedPathAccess.MutationHook() {
                public void beforeMutation(Path path) { }
                public void afterCreation(Path path) throws IOException { if (path.getFileName().toString().startsWith(".GeneratedPatches-staging-")) { Files.move(path, fileSystem.getPath("/parked-create")); Files.createSymbolicLink(path, outside); } }
            });
            var publication = new StartupPackPublisher(layout, id -> registered.set(true), new StartupPackPublisher.FileMoveStrategy(), (s, p) -> { }, Files::delete, access).publish(plan());
            assertFalse(publication.published()); assertFalse(registered.get()); assertEquals("untouched", Files.readString(outside.resolve("marker.txt"))); assertFalse(Files.exists(outside.resolve("Server/Test.json")));
        }
    }

    @Test
    void postCreateOrdinaryDirectoryReplacementFailsBeforeStaleContentCanActivate() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); AtomicBoolean registered = new AtomicBoolean();
        OwnedPathAccess access = new OwnedPathAccess(layout, new OwnedPathAccess.MutationHook() {
            public void beforeMutation(Path path) { }
            public void afterCreation(Path path) throws IOException { if (path.getFileName().toString().startsWith(".GeneratedPatches-staging-")) { Files.delete(path); Files.createDirectory(path); Files.writeString(path.resolve("stale.json"), "stale"); } }
        });
        var publication = new StartupPackPublisher(layout, id -> registered.set(true), new StartupPackPublisher.FileMoveStrategy(), (s, p) -> { }, Files::delete, access).publish(plan());
        assertFalse(publication.published()); assertFalse(registered.get()); assertFalse(Files.exists(layout.generatedRoot().resolve("stale.json")));
    }

    @Test
    void secureDescriptorCleanupDoesNotFollowSwapAfterTreeOpen() throws Exception {
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/owned/staging"));
            Files.writeString(root.resolve("inside.txt"), "inside");
            Path outside = Files.createDirectories(fileSystem.getPath("/outside")); Files.writeString(outside.resolve("marker.txt"), "untouched");
            try { SecureOwnedDirectories.deleteOwnedTree(root, () -> { Files.move(root, fileSystem.getPath("/parked")); Files.createSymbolicLink(root, outside); }); } catch (IOException expected) { }
            assertEquals("untouched", Files.readString(outside.resolve("marker.txt")));
        }
    }

    @Test
    void secureDescriptorMoveDoesNotFollowSourceSwapAfterParentsOpen() throws Exception {
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path source = Files.createDirectories(fileSystem.getPath("/owned/source")); Files.writeString(source.resolve("inside.txt"), "inside");
            Path target = fileSystem.getPath("/owned/target"); Path outside = Files.createDirectories(fileSystem.getPath("/outside")); Files.writeString(outside.resolve("marker.txt"), "untouched");
            try { SecureOwnedDirectories.moveOwnedDirectory(source, target, () -> { Files.move(source, fileSystem.getPath("/parked-move")); Files.createSymbolicLink(source, outside); }); } catch (IOException expected) { }
            assertEquals("untouched", Files.readString(outside.resolve("marker.txt")));
        }
    }

    @Test
    void refusesPathsOutsideOwnedDataRoot() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        assertFalse(layout.isOwned(layout.dataRoot().getParent()));
        assertFalse(layout.isOwned(temporary.resolve("escape")));
    }

    @Test
    void retainsActivationEvidenceWithoutPresentingItAsCurrentWhenRegistrationFails() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        StartupPackPublisher publisher = new StartupPackPublisher(layout, attemptRegistrar(() -> { throw new IOException("registration failed"); }, () -> { }));

        StartupPackPublisher.Publication publication = publisher.publish(plan());

        assertFalse(publication.published());
        assertFalse(Files.exists(layout.generatedRoot()));
        assertEquals(null, publication.activeRoot());
        assertTrue(Files.isDirectory(layout.diagnosticsRoot()));
    }

    @Test
    void rejectsLinksInEveryOwnedAncestryAndNeverTouchesOutsideContent() throws Exception {
        assertLinkedPathRejected("server", root -> Files.createSymbolicLink(root.getParent().resolve("server"), root.getParent().resolve("outside")));
        assertLinkedPathRejected("mods", root -> { Files.createDirectories(root); Files.createSymbolicLink(root.resolve("mods"), root.getParent().resolve("outside")); });
        assertLinkedPathRejected("data", root -> { Files.createDirectories(root.resolve("mods")); Files.createSymbolicLink(root.resolve("mods/Alechilles_Patchwork"), root.getParent().resolve("outside")); });
        assertLinkedPathRejected("diagnostics", root -> { GeneratedPackLayout layout = new GeneratedPackLayout(root); Files.createDirectories(layout.generatedRoot()); Files.createSymbolicLink(layout.diagnosticsRoot(), root.getParent().resolve("outside")); });
        assertLinkedPathRejected("live", root -> { GeneratedPackLayout layout = new GeneratedPackLayout(root); Files.createDirectories(layout.dataRoot()); Files.createSymbolicLink(layout.generatedRoot(), root.getParent().resolve("outside")); });
        assertLinkedPathRejected("staging", root -> { GeneratedPackLayout layout = new GeneratedPackLayout(root); Files.createDirectories(layout.dataRoot()); Files.createSymbolicLink(layout.stagingRoot("00000000-0000-0000-0000-000000000003"), root.getParent().resolve("outside")); });
    }

    @Test
    void ordinaryNonLinkLayoutRemainsPublishable() {
        var publication = new StartupPackPublisher(new GeneratedPackLayout(temporary), id -> { }).publish(plan());
        assertTrue(publication.published(), publication.diagnostic());
    }

    @Test
    void cleanupFailureIsReportedAndPreventsActivationAndRegistration() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        Path owned = layout.stagingRoot("00000000-0000-0000-0000-000000000004");
        Files.createDirectories(owned.resolve("nested")); Files.writeString(owned.resolve("nested/partial.json"), "partial");
        AtomicBoolean registered = new AtomicBoolean();
        StartupPackPublisher publisher = new StartupPackPublisher(layout, id -> registered.set(true), new StartupPackPublisher.FileMoveStrategy(), (staging, current) -> { },
                path -> { if (path.getFileName().toString().equals("partial.json")) throw new IOException("injected cleanup failure"); Files.delete(path); });
        var publication = publisher.publish(plan());
        assertFalse(publication.published()); assertFalse(registered.get()); assertFalse(Files.exists(layout.generatedRoot()));
        assertTrue(publication.residualEvidence().contains(owned)); assertTrue(Files.exists(owned.resolve("nested/partial.json")));
    }

    @Test
    void registrationFailureWithoutPriorRetainsExactlyOneFailedNewEvidence() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        var publication = new StartupPackPublisher(layout, attemptRegistrar(() -> { throw new IOException("registration failed"); }, () -> { })).publish(plan());
        assertFalse(publication.published()); assertFalse(Files.exists(layout.generatedRoot()));
        assertEquals(1, publication.recoveryEvidence().size()); assertTrue(publication.recoveryEvidence().getFirst().getFileName().toString().startsWith("GeneratedPatches-failed-new-"));
        assertTrue(Files.exists(publication.recoveryEvidence().getFirst().resolve("Server/Test.json")));
    }

    @Test
    void registrationFailureWithPriorRetainsDistinctPriorAndFailedNewEvidence() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        Files.createDirectories(layout.generatedRoot()); Files.writeString(layout.generatedRoot().resolve("old.json"), "old");
        var publication = new StartupPackPublisher(layout, attemptRegistrar(() -> { throw new IOException("registration failed"); }, () -> { })).publish(plan());
        assertFalse(publication.published()); assertFalse(Files.exists(layout.generatedRoot())); assertEquals(2, publication.recoveryEvidence().size());
        Path prior = publication.recoveryEvidence().stream().filter(path -> path.getFileName().toString().startsWith("GeneratedPatches-prior-")).findFirst().orElseThrow();
        Path failedNew = publication.recoveryEvidence().stream().filter(path -> path.getFileName().toString().startsWith("GeneratedPatches-failed-new-")).findFirst().orElseThrow();
        assertEquals("old", Files.readString(prior.resolve("old.json"))); assertTrue(Files.exists(failedNew.resolve("Server/Test.json")));
    }

    @Test
    void repeatedPublicationsUseUniqueQuarantinesAndDoNotRestoreStaleTargets() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        Files.createDirectories(layout.generatedRoot()); Files.writeString(layout.generatedRoot().resolve("stale-a.json"), "a");
        assertTrue(new StartupPackPublisher(layout, id -> { }).publish(planWith("new-a.json", "a")).published());
        assertTrue(new StartupPackPublisher(layout, id -> { }).publish(planWith("new-b.json", "b")).published());
        try (var paths = Files.list(layout.diagnosticsRoot())) {
            List<String> names = paths.map(path -> path.getFileName().toString()).toList();
            assertEquals(2, names.size()); assertEquals(2, names.stream().distinct().count());
        }
        assertTrue(Files.exists(layout.generatedRoot().resolve("new-b.json"))); assertFalse(Files.exists(layout.generatedRoot().resolve("stale-a.json"))); assertFalse(Files.exists(layout.generatedRoot().resolve("new-a.json")));
    }

    @Test
    void failedRecoveryMoveExposesResidualSeparatelyFromQuarantinedEvidence() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); AtomicBoolean registrarCalled = new AtomicBoolean();
        StartupPackPublisher.MoveStrategy moves = new StartupPackPublisher.MoveStrategy() {
            @Override public void atomicMove(Path from, Path to) throws IOException { if (from.equals(layout.generatedRoot())) throw new IOException("recovery move failed"); Files.move(from, to); }
            @Override public void nonAtomicMove(Path from, Path to) throws IOException { atomicMove(from, to); }
        };
        var publication = new StartupPackPublisher(layout, id -> { registrarCalled.set(true); throw new IOException("registration failed"); }, moves, (staging, current) -> { }).publish(plan());
        assertFalse(publication.published()); assertTrue(registrarCalled.get()); assertEquals(List.of(), publication.recoveryEvidence());
        assertEquals(List.of(layout.generatedRoot()), publication.residualEvidence()); assertEquals(null, publication.activeRoot());
    }

    private static PatchGenerationService.GenerationPlan planWith(String target, String value) {
        GeneratedPackManifest manifest = new GeneratedPackManifest(List.of(new GeneratedPackManifest.Entry(target, value.getBytes())));
        return new PatchGenerationService.GenerationPlan(manifest.entries(), new PatchStatusSnapshot(List.of(), java.util.Map.of(), List.of()), manifest);
    }

    private static StartupPackPublisher.MoveStrategy throwAfterMove(String destinationPrefix) {
        return new StartupPackPublisher.MoveStrategy() {
            @Override public void atomicMove(Path from, Path to) throws IOException { move(from, to); }
            @Override public void nonAtomicMove(Path from, Path to) throws IOException { move(from, to); }
            private void move(Path from, Path to) throws IOException { Files.move(from, to); if (to.getFileName().toString().startsWith(destinationPrefix)) throw new IOException("committed then threw"); }
        };
    }
    private static StartupPackPublisher.MoveStrategy throwAfterMoveExact(Path expected) {
        return new StartupPackPublisher.MoveStrategy() {
            public void atomicMove(Path from, Path to) throws IOException { move(from, to); }
            public void nonAtomicMove(Path from, Path to) throws IOException { move(from, to); }
            private void move(Path from, Path to) throws IOException { Files.move(from, to); if (to.equals(expected)) throw new IOException("committed activation"); }
        };
    }
    private static StartupPackPublisher.PackRegistrar attemptRegistrar(ThrowingAction commit, ThrowingAction rollback) {
        return new StartupPackPublisher.PackRegistrar() {
            public void register(String id) { }
            public StartupPackPublisher.RegistrationAttempt prepare(String id) { return new StartupPackPublisher.RegistrationAttempt() { public void commit() throws Exception { commit.run(); } public void rollback() throws Exception { rollback.run(); } }; }
        };
    }
    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }

    private Path staged(PatchGenerationService.GenerationPlan plan) throws Exception {
        Path root = Files.createDirectories(temporary.resolve("staged-" + java.util.UUID.randomUUID()));
        for (var entry : plan.entries()) { Path file = root.resolve(entry.target()); Files.createDirectories(file.getParent()); Files.write(file, entry.bytes()); }
        Files.write(root.resolve("manifest.json"), StartupPackPublisher.hytaleManifest(plan.sourcePackIds()));
        Files.write(root.resolve(GeneratedPackManifest.FILE_NAME), plan.manifest().bytes()); return root;
    }
    private static PatchGenerationService.GenerationPlan planWithSources(List<String> ids) {
        GeneratedPackManifest manifest = new GeneratedPackManifest(List.of(new GeneratedPackManifest.Entry("Server/Test.json", "{}".getBytes())));
        return new PatchGenerationService.GenerationPlan(manifest.entries(), new PatchStatusSnapshot(List.of(), java.util.Map.of(), List.of()), manifest, ids);
    }

    private static void assertLinkedPathRejected(String ignored, LinkSetup setup) throws Exception {
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path outside = Files.createDirectories(fileSystem.getPath("/outside")); Files.writeString(outside.resolve("marker.txt"), "untouched");
            Path root = fileSystem.getPath("/server"); setup.create(root);
            var publication = new StartupPackPublisher(new GeneratedPackLayout(root), id -> { throw new AssertionError("must not register"); }).publish(plan());
            assertFalse(publication.published(), ignored); assertEquals("untouched", Files.readString(outside.resolve("marker.txt")), ignored);
        }
    }

    @FunctionalInterface private interface LinkSetup { void create(Path root) throws Exception; }

    private static PatchGenerationService.GenerationPlan plan() {
        GeneratedPackManifest manifest = new GeneratedPackManifest(List.of(new GeneratedPackManifest.Entry("Server/Test.json", "{}".getBytes())));
        return new PatchGenerationService.GenerationPlan(manifest.entries(), new PatchStatusSnapshot(List.of(), java.util.Map.of(), List.of()), manifest);
    }
}
