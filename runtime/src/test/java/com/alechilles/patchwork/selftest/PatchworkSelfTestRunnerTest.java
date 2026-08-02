package com.alechilles.patchwork.selftest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alechilles.patchwork.generation.GeneratedPackLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

final class PatchworkSelfTestRunnerTest {
    @TempDir Path temporary;

    @Test void cleansOnlyItsUniqueRunDirectoryWithoutTouchingProductionOutput() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary);
        Path production = layout.generatedRoot();
        Files.createDirectories(production);
        Path sentinel = production.resolve("sentinel.json");
        Files.writeString(sentinel, "production");

        PatchworkSelfTestResult result = new PatchworkSelfTestRunner(layout).run(PatchworkSelfTestPack.empty());

        assertTrue(result.cleanupAttempted());
        assertTrue(result.cleanupSucceeded(), result.diagnostic());
        assertTrue(Files.exists(sentinel));
        assertFalse(Files.exists(result.runDirectory()));
    }

    @Test void standardPackRunsRealGenerationIncludingItsModDataCondition() {
        PatchworkSelfTestResult result = new PatchworkSelfTestRunner(new GeneratedPackLayout(temporary)).run(PatchworkSelfTestPack.standard());

        assertTrue(result.completed(), result.diagnostic());
        assertTrue(result.caseOutcomes().stream().allMatch(PatchworkSelfTestResult.CaseOutcome::passed), result.diagnostic());
        assertTrue(result.generatedTargets().contains("Server/PatchworkSelfTest/replace.json"));
        assertTrue(result.generatedTargets().contains("Server/PatchworkSelfTest/condition.json"));
    }

    @Test void standardPackVerifiesEveryBuiltInPatchOperation() {
        PatchworkSelfTestResult result = new PatchworkSelfTestRunner(new GeneratedPackLayout(temporary)).run(PatchworkSelfTestPack.standard());

        assertTrue(result.completed(), result.diagnostic());
        assertEquals(Set.of(
                "Server/PatchworkSelfTest/add.json",
                "Server/PatchworkSelfTest/merge.json",
                "Server/PatchworkSelfTest/replace.json",
                "Server/PatchworkSelfTest/remove.json",
                "Server/PatchworkSelfTest/insert.json",
                "Server/PatchworkSelfTest/replace-matching.json",
                "Server/PatchworkSelfTest/remove-matching.json",
                "Server/PatchworkSelfTest/move-matching.json",
                "Server/PatchworkSelfTest/condition.json"),
                result.caseOutcomes().stream().map(PatchworkSelfTestResult.CaseOutcome::target).collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.caseOutcomes().stream().allMatch(PatchworkSelfTestResult.CaseOutcome::passed), result.diagnostic());
    }

    @Test void falseConditionFailsWithoutChangingProductionOutput() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); Path production = Files.createDirectories(layout.generatedRoot().resolve("nested"));
        Path sentinel = production.resolve("sentinel.json"); Files.writeString(sentinel, "unchanged");
        PatchworkSelfTestPack pack = new PatchworkSelfTestPack(java.util.List.of(new PatchworkSelfTestCase("Server/Test.json", "{\"value\":1}", "Server/Patchwork/Patches/test.json",
                "{\"Id\":\"false\",\"Target\":\"Server/Test.json\",\"When\":{\"ModInstalled\":\"missing\"},\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":2}]}", null, Map.of(), "Server/Test.json", Map.of("/value", "2"))));
        PatchworkSelfTestResult result = new PatchworkSelfTestRunner(layout).run(pack);
        assertFalse(result.completed()); assertTrue(Files.readString(sentinel).equals("unchanged")); assertFalse(Files.exists(result.runDirectory()));
    }

    @Test void expectedMismatchFailsWithoutChangingEveryProductionFile() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary); Path production = Files.createDirectories(layout.generatedRoot());
        Files.writeString(production.resolve("top.json"), "top"); Files.createDirectories(production.resolve("nested/deep")); Files.writeString(production.resolve("nested/deep/value.json"), "deep");
        Map<String, String> before = snapshot(production);
        PatchworkSelfTestPack mismatch = new PatchworkSelfTestPack(java.util.List.of(new PatchworkSelfTestCase("Server/Test.json", "{\"value\":1}", "Server/Patchwork/Patches/test.json",
                "{\"Id\":\"mismatch\",\"Target\":\"Server/Test.json\",\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":2}]}", null, Map.of(), "Server/Test.json", Map.of("/value", "3"))));
        PatchworkSelfTestResult result = new PatchworkSelfTestRunner(layout).run(mismatch);
        assertTrue(result.started()); assertFalse(result.completed()); assertTrue(before.equals(snapshot(production))); assertTrue(result.cleanupSucceeded(), result.diagnostic());
    }

    @Test void injectedReloadOutcomeIsReportedWithoutPretendingDefaultReloadWorks() {
        for (PatchworkSelfTestReloadHandle.ReloadOutcome expected : PatchworkSelfTestReloadHandle.ReloadOutcome.values()) {
            PatchworkSelfTestResult result = new PatchworkSelfTestRunner(new GeneratedPackLayout(temporary.resolve(expected.name())), generation -> expected).run(PatchworkSelfTestPack.standard());
            boolean verified = expected == PatchworkSelfTestReloadHandle.ReloadOutcome.HOT_RELOADED
                    || expected == PatchworkSelfTestReloadHandle.ReloadOutcome.ADAPTER_RELOADED
                    || expected == PatchworkSelfTestReloadHandle.ReloadOutcome.REMOVED
                    || expected == PatchworkSelfTestReloadHandle.ReloadOutcome.RESTART_REQUIRED;
            assertTrue(result.completed() == verified, result.diagnostic()); assertTrue(result.reloadOutcome() == expected);
        }
    }

    @Test void missingExpectedPointerBecomesARecordedFailedCheck() {
        PatchworkSelfTestPack pack = new PatchworkSelfTestPack(java.util.List.of(new PatchworkSelfTestCase("Server/Test.json", "{\"value\":1}", "Server/Patchwork/Patches/test.json",
                "{\"Id\":\"pointer\",\"Target\":\"Server/Test.json\",\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":2}]}", null, Map.of(), "Server/Test.json", Map.of("/missing", "2"))));
        PatchworkSelfTestResult result = new PatchworkSelfTestRunner(new GeneratedPackLayout(temporary.resolve("missing-pointer"))).run(pack);
        assertFalse(result.completed());
        assertFalse(result.caseOutcomes().getFirst().checks().getFirst().passed());
    }

    @Test void productionTreeIsByteIdenticalAfterSuccessReloadFailureAndCancellation() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Path root = temporary.resolve("isolation-" + mode); GeneratedPackLayout layout = new GeneratedPackLayout(root); Path production = production(layout); Map<String, String> before = snapshot(production);
            PatchworkSelfTestRunner runner = mode == 1 ? new PatchworkSelfTestRunner(layout, generation -> { throw new IllegalStateException(); })
                    : mode == 2 ? new PatchworkSelfTestRunner(layout, null, Files::delete, (phase, run) -> { if (phase == PatchworkSelfTestRunner.Phase.BEFORE_GENERATION) runnerCancel.get().cancel(); }) : new PatchworkSelfTestRunner(layout);
            if (mode == 2) { runnerCancel.set(runner); }
            PatchworkSelfTestResult result = runner.run(PatchworkSelfTestPack.standard());
            if (mode == 0) assertTrue(result.completed(), result.diagnostic()); else assertFalse(result.completed());
            assertTrue(result.started()); if (mode == 2) assertTrue(result.cancelled());
            assertTrue(before.equals(snapshot(production)), result.diagnostic());
        }
    }

    @Test void cancellationBeforeRunIsReportedAndTheRunnerCannotBeReused() {
        PatchworkSelfTestRunner runner = new PatchworkSelfTestRunner(new GeneratedPackLayout(temporary.resolve("cancel-before")));
        runner.cancel();

        assertTrue(runner.run(PatchworkSelfTestPack.empty()).cancelled());
        assertThrows(IllegalStateException.class, () -> runner.run(PatchworkSelfTestPack.empty()));
    }

    @Test void cancellationDuringRunIsReportedAndTheRunnerCannotBeReused() {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary.resolve("cancel-during"));
        PatchworkSelfTestRunner runner = new PatchworkSelfTestRunner(layout, null, Files::delete, (phase, run) -> {
            if (phase == PatchworkSelfTestRunner.Phase.BEFORE_GENERATION) runnerCancel.get().cancel();
        });
        runnerCancel.set(runner);

        assertTrue(runner.run(PatchworkSelfTestPack.standard()).cancelled());
        assertThrows(IllegalStateException.class, () -> runner.run(PatchworkSelfTestPack.empty()));
    }

    @Test void unsafeFixturePathsAreRejectedBeforeTheRunnerCanWrite() {
        for (String unsafe : java.util.List.of("../escape.json", "/escape.json", "C:/escape.json", "Server\\..\\escape.json")) {
            assertThrows(IllegalArgumentException.class, () -> new PatchworkSelfTestCase(unsafe, "{}", "Server/Patchwork/Patches/x.json", "{}", null, Map.of(), "Server/X.json", Map.of("/v", "1")));
            assertThrows(IllegalArgumentException.class, () -> new PatchworkSelfTestCase("Server/X.json", "{}", unsafe, "{}", null, Map.of(), "Server/X.json", Map.of("/v", "1")));
            assertThrows(IllegalArgumentException.class, () -> new PatchworkSelfTestCase("Server/X.json", "{}", "Server/Patchwork/Patches/x.json", "{}", "../id", Map.of("x.json", "{}"), "Server/X.json", Map.of("/v", "1")));
            assertThrows(IllegalArgumentException.class, () -> new PatchworkSelfTestCase("Server/X.json", "{}", "Server/Patchwork/Patches/x.json", "{}", null, Map.of(unsafe, "{}"), "Server/X.json", Map.of("/v", "1")));
            assertThrows(IllegalArgumentException.class, () -> new PatchworkSelfTestCase("Server/X.json", "{}", "Server/Patchwork/Patches/x.json", "{}", null, Map.of(), unsafe, Map.of("/v", "1")));
        }
    }

    @Test void linkedFixtureInputAndLinkedSelfTestRootAreRejectedWithoutEscape() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary.resolve("links")); Path outside = Files.createDirectories(temporary.resolve("outside")); Path marker = outside.resolve("marker"); Files.writeString(marker, "outside");
        PatchworkSelfTestRunner runner = new PatchworkSelfTestRunner(layout, null, Files::delete, (phase, run) -> {
            if (phase != PatchworkSelfTestRunner.Phase.AFTER_FIXTURES) return;
            Path patches = run.resolve("source/Server/Patchwork/Patches"); replaceWithLink(patches, outside);
        });
        PatchworkSelfTestResult linked = runner.run(PatchworkSelfTestPack.standard());
        assertFalse(linked.completed()); assertTrue(Files.readString(marker).equals("outside"));
        GeneratedPackLayout rootLinkLayout = new GeneratedPackLayout(temporary.resolve("root-link")); rootLinkLayout.createSafeDataRoot(); linkOrSkip(rootLinkLayout.selfTestRoot(), outside);
        PatchworkSelfTestResult rootLinked = new PatchworkSelfTestRunner(rootLinkLayout).run(PatchworkSelfTestPack.empty());
        assertFalse(rootLinked.started()); assertTrue(Files.readString(marker).equals("outside"));
    }

    @Test void cleanupFailureRetainsOnlyExactRunEvidence() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary.resolve("cleanup")); Path production = production(layout); Map<String, String> before = snapshot(production);
        PatchworkSelfTestResult result = new PatchworkSelfTestRunner(layout, null, path -> { throw new IOException("forced"); }).run(PatchworkSelfTestPack.standard());
        assertTrue(result.cleanupAttempted()); assertFalse(result.cleanupSucceeded()); assertTrue(result.diagnostic().contains("Cleanup failed: IOException"));
        assertTrue(Files.exists(result.runDirectory())); assertTrue(before.equals(snapshot(production))); assertTrue(Files.isDirectory(layout.selfTestRoot()));
        deleteTree(result.runDirectory());
    }

    @Test void replacedRunIsNeverReadOrDeletedDuringFixtureVerificationOrCleanup() throws Exception {
        GeneratedPackLayout layout = new GeneratedPackLayout(temporary.resolve("run-swap"));
        Path attacker = Files.createDirectories(temporary.resolve("attacker-run"));
        Path marker = attacker.resolve("must-survive");
        Files.writeString(marker, "attacker");
        PatchworkSelfTestRunner runner = new PatchworkSelfTestRunner(layout, null, Files::delete, (phase, run) -> {
            if (phase != PatchworkSelfTestRunner.Phase.AFTER_FIXTURES) return;
            try {
                Files.move(run, temporary.resolve("parked-run"));
                Files.createDirectories(run);
                Files.copy(marker, run.resolve(marker.getFileName()));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });

        PatchworkSelfTestResult result = runner.run(PatchworkSelfTestPack.standard());

        assertFalse(result.completed());
        assertTrue(Files.exists(marker));
        assertTrue(Files.exists(result.runDirectory().resolve(marker.getFileName())));
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) { for (Path path : paths.filter(Files::isRegularFile).toList()) result.put(root.relativize(path).toString(), java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path))); }
        return result;
    }
    private static final java.util.concurrent.atomic.AtomicReference<PatchworkSelfTestRunner> runnerCancel = new java.util.concurrent.atomic.AtomicReference<>();
    private static Path production(GeneratedPackLayout layout) throws Exception { Path root = Files.createDirectories(layout.generatedRoot()); Files.writeString(root.resolve("manifest.json"), "manifest"); Files.createDirectories(root.resolve("nested/deep")); Files.writeString(root.resolve("nested/deep/value.json"), "bytes"); return root; }
    private static void deleteTree(Path root) throws IOException { if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return; try (var paths = Files.walk(root)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); } }
    private static void replaceWithLink(Path link, Path target) { try { deleteTree(link); linkOrSkip(link, target); } catch (IOException failure) { throw new IllegalStateException(failure); } }
    private static void linkOrSkip(Path link, Path target) throws IOException { try { Files.createSymbolicLink(link, target); } catch (UnsupportedOperationException | java.nio.file.FileSystemException denied) { Assumptions.assumeTrue(false, "Symbolic links unavailable: " + denied.getClass().getSimpleName()); } }
}
