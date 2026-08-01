package com.alechilles.patchwork.reload;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests exact epoch, target, and hash correlation for reload observations. */
final class PatchReloadTrackerTest {
    @Test
    void acceptsOnlyTheCurrentExpectationAndRejectsStaleEpochs() {
        PatchReloadTracker tracker = new PatchReloadTracker();
        var expected = tracker.expect(7L, "Server/AssetStore/Test.json", "new-hash", false);

        assertFalse(tracker.record(new PatchReloadTracker.Observation(6L, "Server/AssetStore/Test.json", "new-hash", PatchReloadTracker.Outcome.LOADED)));
        assertFalse(tracker.record(new PatchReloadTracker.Observation(7L, "Server/AssetStore/Test.json", "wrong-hash", PatchReloadTracker.Outcome.LOADED)));
        assertTrue(tracker.record(new PatchReloadTracker.Observation(7L, "Server/AssetStore/Test.json", "new-hash", PatchReloadTracker.Outcome.LOADED)));
        assertTrue(expected.isDone());
    }

    @Test
    void recordsObserverFailureForTheExactCurrentExpectation() {
        PatchReloadTracker tracker = new PatchReloadTracker();
        var expected = tracker.expect(3L, "Server/NPC/Test.json", "hash", false);

        assertTrue(tracker.record(new PatchReloadTracker.Observation(3L, "Server/NPC/Test.json", "hash", PatchReloadTracker.Outcome.FAILED)));
        assertTrue(expected.isCompletedExceptionally());
    }

    @Test
    void rejectsLoadedObservationForAnExpectedRemoval() {
        PatchReloadTracker tracker = new PatchReloadTracker();
        var expected = tracker.expect(4L, "Server/Common/Removed.json", PatchReloadTracker.REMOVED_HASH, true);

        assertFalse(tracker.record(new PatchReloadTracker.Observation(4L, "Server/Common/Removed.json", PatchReloadTracker.REMOVED_HASH, PatchReloadTracker.Outcome.LOADED)));
        assertTrue(expected.isCompletedExceptionally());
    }

    @Test
    void rejectsRemovedObservationForAnExpectedReplacement() {
        PatchReloadTracker tracker = new PatchReloadTracker();
        var expected = tracker.expect(4L, "Server/Common/Present.json", "hash", false);

        assertFalse(tracker.record(new PatchReloadTracker.Observation(4L, "Server/Common/Present.json", "hash", PatchReloadTracker.Outcome.REMOVED)));
        assertTrue(expected.isCompletedExceptionally());
    }
}
