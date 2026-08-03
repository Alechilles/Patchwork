package com.alechilles.patchwork.reload;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.patchwork.generation.StartupPackPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HytaleReloadEvidenceCorrelatorTest {
    @TempDir Path temporary;

    @Test
    void requiresGeneratedProviderExpectedPathAndDiskHash() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated/Server/AssetStore"));
        Path target = root.resolve("A.json");
        Files.writeString(target, "new");
        PatchReloadTracker tracker = new PatchReloadTracker();
        String token = "pass:1";
        String asset = "Server/AssetStore/A.json";
        String hash = TargetJournalEntry.hash("new".getBytes());
        tracker.activate(token);
        var future = tracker.expect(token, 1L, asset, hash, false);
        HytaleReloadEvidenceCorrelator correlator = new HytaleReloadEvidenceCorrelator(tracker, temporary.resolve("generated"));
        correlator.expect(new HytaleReloadEvidenceCorrelator.Expectation(token, 1L, asset, hash, false, StartupPackPublisher.PACK_ID));

        assertFalse(correlator.confirm(new HytaleReloadEvidenceCorrelator.Evidence(token, 1L, asset, "Pack:Other", asset, false)));
        assertFalse(future.isDone());
        assertTrue(correlator.confirm(new HytaleReloadEvidenceCorrelator.Evidence(token, 1L, asset, StartupPackPublisher.PACK_ID, asset, false)));
        assertTrue(future.isDone());
    }

    @Test
    void removalRequiresTheExpectedGeneratedPathToBeAbsent() throws Exception {
        Path generated = Files.createDirectories(temporary.resolve("generated/Server/Common"));
        String target = "Server/Common/A.json";
        String token = "pass:2";
        PatchReloadTracker tracker = new PatchReloadTracker();
        tracker.activate(token);
        var future = tracker.expect(token, 2L, target, PatchReloadTracker.REMOVED_HASH, true);
        HytaleReloadEvidenceCorrelator correlator = new HytaleReloadEvidenceCorrelator(tracker, temporary.resolve("generated"));
        correlator.expect(new HytaleReloadEvidenceCorrelator.Expectation(token, 2L, target, PatchReloadTracker.REMOVED_HASH, true, StartupPackPublisher.PACK_ID));

        Files.writeString(generated.resolve("A.json"), "still here");
        assertFalse(correlator.confirm(new HytaleReloadEvidenceCorrelator.Evidence(token, 2L, target, StartupPackPublisher.PACK_ID, target, true)));
        Files.delete(generated.resolve("A.json"));
        assertTrue(correlator.confirm(new HytaleReloadEvidenceCorrelator.Evidence(token, 2L, target, "Pack:Base", target, true)));
        assertTrue(future.isDone());
    }

    @Test
    void monitorHostEpochDoesNotBlockCurrentCoordinatorEpochConfirmation() throws Exception {
        Path generated = Files.createDirectories(temporary.resolve("generated/Server/AssetStore"));
        String target = "Server/AssetStore/A.json";
        Files.writeString(generated.resolve("A.json"), "new");
        String token = "coordinator:reload-2";
        String hash = TargetJournalEntry.hash("new".getBytes());
        PatchReloadTracker tracker = new PatchReloadTracker();
        tracker.activate(token);
        var future = tracker.expect(token, 42L, target, hash, false);
        HytaleReloadEvidenceCorrelator correlator = new HytaleReloadEvidenceCorrelator(tracker, temporary.resolve("generated"));
        correlator.expect(new HytaleReloadEvidenceCorrelator.Expectation(token, 42L, target, hash, false, StartupPackPublisher.PACK_ID));

        // The bridge's elected host epoch is intentionally unrelated to the
        // coordinator's per-reload epoch (42). The provider/path evidence still
        // correlates to the current expectation.
        assertTrue(correlator.confirmAny(7L, StartupPackPublisher.PACK_ID, target, false));
        assertTrue(future.isDone());
    }
}
