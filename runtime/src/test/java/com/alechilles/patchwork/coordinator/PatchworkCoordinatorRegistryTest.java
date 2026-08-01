package com.alechilles.patchwork.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exercises real global election, ownership fencing, and replacement recovery. */
final class PatchworkCoordinatorRegistryTest {
    @AfterEach void clearRegistry() { PatchworkCoordinatorRegistry.clearForTests(); }

    @Test
    void serializesGlobalRegistrationAndFencesDrainsBeforeStartingReplacement() {
        // Catches a mutation that publishes the replacement before revoking, draining, and deactivating its predecessor.
        var events = new ArrayList<String>(); var registry = PatchworkCoordinatorRegistry.current();
        var oldToken = registry.registerCandidate(candidate("old", "1.0.0", events, false));
        var newToken = registry.registerCandidate(candidate("new", "2.0.0", events, false));

        assertNotEquals(oldToken, newToken); assertEquals("new", registry.activeProviderId());
        assertEquals(List.of("old:activate:1", "old:start:1", "old:fence:1", "old:drain:1", "old:deactivate:1", "new:activate:2", "new:start:2"), events);
        assertFalse(registry.publish(oldToken)); assertTrue(registry.publish(newToken));
    }

    @Test
    void ignoresStaleUnregisterAndRestoresPriorWinnerWhenReplacementFailsToStart() {
        // Catches a mutation that permits an old handle to remove its replacement or leaves no owner after a failed handoff.
        var events = new ArrayList<String>(); var registry = PatchworkCoordinatorRegistry.current();
        var oldToken = registry.registerCandidate(candidate("same", "1.0.0", events, false));
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("same", "2.0.0", events, true), oldToken));

        assertEquals("same", registry.activeProviderId());
        assertTrue(events.contains("same:fence:2")); assertTrue(events.contains("same:drain:2")); assertTrue(events.contains("same:deactivate:2"));
        assertTrue(events.contains("same:activate:3")); assertTrue(registry.publish(registry.activeToken()));
    }

    @Test
    void usesOnePropertyKeyAndNeverGivesIncompatibleCandidateOwnership() {
        // Catches protocol-version-specific properties or election of an incompatible descriptor.
        var registry = PatchworkCoordinatorRegistry.current();
        registry.registerCandidate(candidate("compatible", "1.0.0", new ArrayList<>(), false));
        registry.registerCandidate(new PatchworkRuntimeCandidate("incompatible", PatchworkRuntimeOrigin.STANDALONE, "9.0.0", 99, "p", "1",
                Path.of("mods", "incompatible.jar"), Path.of("mods", "incompatible"), new RecordingBridge("bad", new ArrayList<>(), false, false)));
        assertEquals("compatible", registry.activeProviderId());
        assertTrue(System.getProperties().containsKey(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY));
    }

    @Test
    void activeUnregisterDrainsAndDeactivatesBeforeNextOwnerStarts() {
        // Catches removal of the active record before its retiring lifecycle is executed.
        var events = new ArrayList<String>(); var registry = PatchworkCoordinatorRegistry.current();
        var active = registry.registerCandidate(candidate("old", "2.0.0", events, false));
        registry.registerCandidate(candidate("next", "1.0.0", events, false));
        registry.unregister(active);
        assertEquals(List.of("old:activate:1", "old:start:1", "old:fence:1", "old:drain:1", "old:deactivate:1", "next:activate:2", "next:start:2"), events);
    }

    @Test
    void drainFailureFailsClosedWithoutAReplacementOrPublicationLease() {
        // Catches retaining an advertised old lease after its fence/drain sequence may have partially retired it.
        var events = new ArrayList<String>(); var registry = PatchworkCoordinatorRegistry.current();
        var old = registry.registerCandidate(candidate("old", "1.0.0", events, false, true));
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("next", "2.0.0", events, false, false)));
        assertFalse(registry.publish(old)); assertEquals(null, registry.localActiveProviderId());
        assertFalse(events.stream().anyMatch(event -> event.startsWith("next:activate")));
    }

    @Test
    void failedPriorRecoveryElectsNextEligibleAtHigherEpoch() {
        // Catches leaving the registry inactive when the prior cannot be restarted.
        var events = new ArrayList<String>(); var registry = PatchworkCoordinatorRegistry.current();
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        PatchworkCoordinatorBridge priorBridge = new PatchworkCoordinatorBridge() { public void fence(long epoch) { events.add("prior:fence:" + epoch); } public void stopAcceptingAndDrain(long epoch) { events.add("prior:drain:" + epoch); } public void deactivate(long epoch) { events.add("prior:deactivate:" + epoch); } public void activate(long epoch) { events.add("prior:activate:" + epoch); } public void start(long epoch) { events.add("prior:start:" + epoch); if (starts.incrementAndGet() > 1) throw new IllegalStateException("prior recovery"); } };
        registry.registerCandidate(new PatchworkRuntimeCandidate("prior", PatchworkRuntimeOrigin.STANDALONE, "3.0.0", 1, "prior", "1", Path.of("mods/prior"), Path.of("mods/prior"), priorBridge));
        registry.registerCandidate(candidate("next", "1.0.0", events, false));
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("failed", "4.0.0", events, true)));
        assertEquals("next", registry.activeProviderId()); assertTrue(events.stream().anyMatch(event -> event.startsWith("next:activate:") && Integer.parseInt(event.substring(event.lastIndexOf(':') + 1)) > 2));
    }

    @Test
    void failedPriorRecoveryStartIsCleanedBeforeNextEligibleOwnerActivates() {
        // Catches activating a fallback while a prior owner that restarted at its recovery epoch remains unfenced.
        var events = new ArrayList<String>(); var registry = PatchworkCoordinatorRegistry.current();
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        PatchworkCoordinatorBridge prior = new PatchworkCoordinatorBridge() {
            @Override public void fence(long epoch) { events.add("prior:fence:" + epoch); }
            @Override public void stopAcceptingAndDrain(long epoch) { events.add("prior:drain:" + epoch); }
            @Override public void deactivate(long epoch) { events.add("prior:deactivate:" + epoch); }
            @Override public void activate(long epoch) { events.add("prior:activate:" + epoch); }
            @Override public void start(long epoch) { events.add("prior:start:" + epoch); if (starts.incrementAndGet() == 2) throw new IllegalStateException("recovery start"); }
        };
        registry.registerCandidate(new PatchworkRuntimeCandidate("prior", PatchworkRuntimeOrigin.STANDALONE, "3.0.0", 1, "prior", "1", Path.of("mods/prior"), Path.of("mods/prior"), prior));
        registry.registerCandidate(candidate("next", "1.0.0", events, false));
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("failed", "4.0.0", events, true)));
        assertEquals(List.of("prior:activate:1", "prior:start:1", "prior:fence:1", "prior:drain:1", "prior:deactivate:1",
                "failed:activate:2", "failed:start:2", "failed:fence:2", "failed:drain:2", "failed:deactivate:2",
                "prior:activate:3", "prior:start:3", "prior:fence:3", "prior:drain:3", "prior:deactivate:3",
                "next:activate:4", "next:start:4"), events);
        assertEquals("next", registry.activeProviderId());
    }

    @Test
    void failedPriorRecoveryCleanupFailsClosedBeforeAnyFallbackCanActivate() {
        // Catches advancing to a fallback after recovery left the former owner partially active.
        var events = new ArrayList<String>(); var registry = PatchworkCoordinatorRegistry.current();
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        PatchworkCoordinatorBridge prior = new PatchworkCoordinatorBridge() {
            @Override public void fence(long epoch) { events.add("prior:fence:" + epoch); }
            @Override public void stopAcceptingAndDrain(long epoch) { events.add("prior:drain:" + epoch); }
            @Override public void deactivate(long epoch) { events.add("prior:deactivate:" + epoch); if (epoch == 3) throw new IllegalStateException("recovery cleanup"); }
            @Override public void activate(long epoch) { events.add("prior:activate:" + epoch); }
            @Override public void start(long epoch) { events.add("prior:start:" + epoch); if (starts.incrementAndGet() == 2) throw new IllegalStateException("recovery start"); }
        };
        var old = registry.registerCandidate(new PatchworkRuntimeCandidate("prior", PatchworkRuntimeOrigin.STANDALONE, "3.0.0", 1, "prior", "1", Path.of("mods/prior"), Path.of("mods/prior"), prior));
        var next = registry.registerCandidate(candidate("next", "1.0.0", events, false));
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("failed", "4.0.0", events, true)));
        assertEquals(null, registry.activeProviderId()); assertFalse(registry.publish(old)); assertFalse(registry.publish(next));
        assertFalse(events.stream().anyMatch(event -> event.startsWith("next:activate")));
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("later", "5.0.0", events, false)));
    }

    @Test
    void sameProviderReplacementRejectsStaleTokenWithoutRemovingNewRegistration() {
        // Catches retaining duplicate same-provider registrations or allowing an old handle to affect its replacement.
        var registry = PatchworkCoordinatorRegistry.current(); var events = new ArrayList<String>();
        var oldToken = registry.registerCandidate(candidate("same", "1.0.0", events, false));
        var newToken = registry.registerCandidate(candidate("same", "2.0.0", events, false), oldToken);
        registry.unregister(oldToken);
        assertEquals("same", registry.activeProviderId()); assertFalse(registry.publish(oldToken)); assertTrue(registry.publish(newToken));
    }

    @Test
    void rejectsSameProviderReplacementWhenOldDrainFailsClosed() {
        // Catches keeping an old same-provider lease publishable after its lifecycle retirement fails.
        var registry = PatchworkCoordinatorRegistry.current(); var events = new ArrayList<String>();
        var old = registry.registerCandidate(candidate("same", "1.0.0", events, false, true));
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("same", "2.0.0", events, false), old));
        assertEquals(null, registry.activeToken()); assertFalse(registry.publish(old)); assertEquals(1, registry.candidateCount());
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("next", "3.0.0", events, false)));
        assertEquals(null, registry.activeToken()); assertEquals(1, registry.candidateCount());
    }

    @Test
    void restoresExactOldTokenAfterSafelyCleaningFailedSameProviderReplacement() {
        // Catches recovery creating a replacement token for the old owner or returning a dead new token.
        var registry = PatchworkCoordinatorRegistry.current(); var events = new ArrayList<String>();
        var old = registry.registerCandidate(candidate("same", "1.0.0", events, false));
        long before = registry.ownershipEpoch();
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("same", "2.0.0", events, true), old));
        assertSame(old, registry.activeToken()); assertTrue(registry.publish(old)); assertEquals(1, registry.candidateCount());
        assertTrue(registry.ownershipEpoch() > before);
    }

    @Test
    void cleanupFailureAfterPartialReplacementFailsClosed() {
        // Catches starting an old or next owner after a partially started replacement could not be cleaned up.
        var registry = PatchworkCoordinatorRegistry.current(); var events = new ArrayList<String>();
        var old = registry.registerCandidate(candidate("old", "1.0.0", events, false));
        PatchworkCoordinatorBridge unsafe = new RecordingBridge("unsafe", events, true, false) {
            @Override public void deactivate(long epoch) { super.deactivate(epoch); throw new IllegalStateException("cleanup"); }
        };
        var candidate = new PatchworkRuntimeCandidate("unsafe", PatchworkRuntimeOrigin.STANDALONE, "2.0.0", 1, "unsafe", "1", Path.of("mods/unsafe"), Path.of("mods/unsafe"), unsafe);
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate));
        assertEquals(null, registry.localActiveProviderId()); assertFalse(registry.publish(old));
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("next", "3.0.0", events, false)));
    }

    @Test
    void blocksBridgePublishReentryWithoutChangingItsPublicationLease() {
        // Catches a bridge callback recursively registering or publishing while its lease is being used.
        var registry = PatchworkCoordinatorRegistry.current(); var events = new ArrayList<String>();
        PatchworkCoordinatorBridge reentrant = new PatchworkCoordinatorBridge() {
            @Override public boolean publish(long epoch) {
                assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("intruder", "9.0.0", events, false)));
                assertFalse(registry.publish(registry.activeToken()));
                return true;
            }
        };
        var token = registry.registerCandidate(new PatchworkRuntimeCandidate("owner", PatchworkRuntimeOrigin.STANDALONE, "1.0.0", 1, "owner", "1", Path.of("mods/owner"), Path.of("mods/owner"), reentrant));
        assertTrue(registry.publish(token)); assertSame(token, registry.activeToken()); assertEquals(1, registry.candidateCount());
    }

    @Test
    void publicJdkOnlyRegistrationThrowsAfterRestoringThePreviousOwner() {
        // Catches a foreign registration API returning a removed token after its bridge fails to start.
        String old = PatchworkCoordinatorRegistry.register(descriptor("public-old", "1.0.0", new PublicStableBridge()));
        assertThrows(IllegalStateException.class, () -> PatchworkCoordinatorRegistry.register(descriptor("public-new", "2.0.0", new PublicFailingBridge())));
        assertEquals("public-old", PatchworkCoordinatorRegistry.activeProviderId()); assertTrue(PatchworkCoordinatorRegistry.publish(old));
    }

    @Test
    void sameProviderLowerVersionRegistrationReplacesTheActiveBridge() {
        // Catches election retaining a higher-version old descriptor when its provider re-registers a lower version.
        var registry = PatchworkCoordinatorRegistry.current(); var events = new ArrayList<String>();
        var old = registry.registerCandidate(candidate("same", "2.0.0", events, false));
        var replacement = registry.registerCandidate(candidate("same", "1.0.0", events, false), old);
        assertSame(replacement, registry.activeToken()); assertFalse(registry.publish(old)); assertTrue(registry.publish(replacement));
        assertEquals(1, registry.candidateCount());
    }

    @Test
    void sameProviderTieLosingRegistrationReplacesTheActiveBridge() {
        // Catches a source-path tie-break retaining an earlier same-provider bridge rather than its latest registration.
        var registry = PatchworkCoordinatorRegistry.current(); var events = new ArrayList<String>();
        var old = registry.registerCandidate(candidate("same", "1.0.0", events, false));
        var replacement = registry.registerCandidate(candidate("same", "1.0.0", events, false), old);
        assertSame(replacement, registry.activeToken()); assertFalse(registry.publish(old)); assertTrue(registry.publish(replacement));
        assertEquals(1, registry.candidateCount());
    }

    @Test
    void crossProviderWinnerRetainsPriorCandidateForFallbackAfterUnregister() {
        // Catches deleting a displaced provider during cross-provider handoff instead of retaining it as passive fallback.
        var registry = PatchworkCoordinatorRegistry.current(); var events = new ArrayList<String>();
        var a = registry.registerCandidate(candidate("a", "1.0.0", events, false));
        var b = registry.registerCandidate(candidate("b", "2.0.0", events, false));
        assertSame(b, registry.activeToken()); assertEquals(2, registry.candidateCount());
        registry.unregister(b);
        assertSame(a, registry.activeToken()); assertEquals(1, registry.candidateCount());
        assertFalse(registry.publish(b)); assertTrue(registry.publish(a));
        assertTrue(registry.ownershipEpoch() > 2);
    }

    @Test
    void publicDescriptorRejectsNonIntegralOrOutOfRangeCoordinatorAbi() {
        // Catches Number.intValue truncation making an incompatible public descriptor appear ABI-compatible.
        assertThrows(IllegalArgumentException.class, () -> PatchworkCoordinatorRegistry.register(descriptorWithAbi(1.5)));
        assertThrows(IllegalArgumentException.class, () -> PatchworkCoordinatorRegistry.register(descriptorWithAbi(new BigInteger("4294967297"))));
    }

    @Test
    void rejectsMissingStaleAndWrongProviderReplacementTokensBeforeLifecycleEvents() {
        // Catches unauthorized same-provider bridge replacement reaching fence/drain/start lifecycle work.
        var registry = PatchworkCoordinatorRegistry.current(); var events = new ArrayList<String>();
        var owner = registry.registerCandidate(candidate("same", "1.0.0", events, false));
        var other = registry.registerCandidate(candidate("other", "1.0.0", events, false));
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("same", "2.0.0", events, false)));
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("same", "2.0.0", events, false), other));
        var fresh = registry.registerCandidate(candidate("same", "2.0.0", events, false), owner);
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("same", "3.0.0", events, false), owner));
        assertTrue(registry.publish(fresh));
    }

    @Test
    void rejectsAnotherProvidersTokenForBrandNewLocalProviderBeforeLifecycleEvents() {
        // Catches treating an extraneous replacement handle as harmless when the incoming provider has no incumbent.
        var registry = PatchworkCoordinatorRegistry.current(); var events = new ArrayList<String>();
        var other = registry.registerCandidate(candidate("other", "1.0.0", events, false));
        var before = List.copyOf(events);
        assertThrows(IllegalStateException.class, () -> registry.registerCandidate(candidate("new", "2.0.0", events, false), other));
        assertEquals(before, events); assertEquals(1, registry.candidateCount()); assertTrue(registry.publish(other));
    }

    @Test
    void publicReplacementRejectsUnknownAndWrongProviderTokensBeforeLifecycleEvents() {
        // Catches public descriptor parsing turning stale handles into an implicit missing replacement authorization.
        var events = new ArrayList<String>();
        String same = PatchworkCoordinatorRegistry.register(descriptor("same", "1.0.0", new PublicRecordingBridge("same", events)));
        String other = PatchworkCoordinatorRegistry.register(descriptor("other", "1.0.0", new PublicRecordingBridge("other", events)));
        var before = List.copyOf(events);
        assertThrows(IllegalStateException.class, () -> PatchworkCoordinatorRegistry.register(descriptor("same", "2.0.0", new PublicRecordingBridge("bad", events), "stale-token")));
        assertThrows(IllegalStateException.class, () -> PatchworkCoordinatorRegistry.register(descriptor("same", "2.0.0", new PublicRecordingBridge("bad", events), other)));
        assertEquals(before, events); assertFalse(events.stream().anyMatch(event -> event.startsWith("bad:")));
    }

    @Test
    void publicReplacementRejectsAnotherProvidersTokenForBrandNewProviderBeforeLifecycleEvents() {
        // Catches descriptor registration accepting a valid but unrelated provider token before election begins.
        var events = new ArrayList<String>();
        String other = PatchworkCoordinatorRegistry.register(descriptor("other", "1.0.0", new PublicRecordingBridge("other", events)));
        var before = List.copyOf(events);
        assertThrows(IllegalStateException.class, () -> PatchworkCoordinatorRegistry.register(descriptor("new", "2.0.0", new PublicRecordingBridge("new", events), other)));
        assertEquals(before, events); assertEquals("other", PatchworkCoordinatorRegistry.activeProviderId()); assertTrue(PatchworkCoordinatorRegistry.publish(other));
    }

    @Test
    void rejectsMalformedSemVerBeforeInitialLocalOrPublicRegistration() {
        // Catches malformed initial descriptors becoming active before comparator parsing occurs.
        assertThrows(IllegalArgumentException.class, () -> candidate("bad", "1.0", new ArrayList<>(), false));
        assertThrows(IllegalArgumentException.class, () -> PatchworkCoordinatorRegistry.register(descriptor("bad", "1.0", new PublicStableBridge())));
    }

    @Test
    void bridgePublishExceptionReturnsFalseAndReleasesPublicationGuard() {
        // Catches a bridge exception leaking from publish or leaving the publication guard locked.
        var registry = PatchworkCoordinatorRegistry.current();
        PatchworkCoordinatorBridge bridge = new PatchworkCoordinatorBridge() { int calls; @Override public boolean publish(long epoch) { if (calls++ == 0) throw new IllegalStateException("publish"); return true; } };
        var token = registry.registerCandidate(new PatchworkRuntimeCandidate("publish", PatchworkRuntimeOrigin.STANDALONE, "1.0.0", 1, "publish", "1", Path.of("mods/publish"), Path.of("mods/publish"), bridge));
        assertFalse(registry.publish(token)); assertTrue(registry.publish(token)); assertSame(token, registry.activeToken());
    }

    private static PatchworkRuntimeCandidate candidate(String id, String version, List<String> events, boolean failStart) {
        return candidate(id, version, events, failStart, false);
    }
    private static PatchworkRuntimeCandidate candidate(String id, String version, List<String> events, boolean failStart, boolean failDrain) {
        return new PatchworkRuntimeCandidate(id, PatchworkRuntimeOrigin.STANDALONE, version, PatchworkCoordinatorRegistry.COORDINATOR_ABI,
                id + ".plugin", "1", Path.of("mods", id + ".jar"), Path.of("mods", id), new RecordingBridge(id, events, failStart, failDrain));
    }

    private static Map<String, Object> descriptor(String id, String version, Object bridge) {
        return Map.of("providerId", id, "origin", "STANDALONE", "runtimeVersion", version, "coordinatorAbi", 1,
                "providerPluginId", id, "providerPluginVersion", "1", "sourceJarPath", Path.of("mods", id + ".jar"),
                "providerDataRoot", Path.of("mods", id), "bridge", bridge);
    }

    private static Map<String, Object> descriptor(String id, String version, Object bridge, String replacementToken) {
        var values = new java.util.HashMap<>(descriptor(id, version, bridge)); values.put("replacementToken", replacementToken); return values;
    }

    private static Map<String, Object> descriptorWithAbi(Number abi) {
        return Map.of("providerId", "abi", "origin", "STANDALONE", "runtimeVersion", "1.0.0", "coordinatorAbi", abi,
                "providerPluginId", "abi", "providerPluginVersion", "1", "sourceJarPath", Path.of("mods/abi.jar"),
                "providerDataRoot", Path.of("mods/abi"), "bridge", new PublicStableBridge());
    }

    public static class PublicStableBridge {
        public void fence(long epoch) { }
        public void stopAcceptingAndDrain(long epoch) { }
        public void deactivate(long epoch) { }
        public void activate(long epoch) { }
        public void start(long epoch) { }
        public boolean publish(long epoch) { return true; }
    }

    public static final class PublicFailingBridge extends PublicStableBridge {
        @Override public void start(long epoch) { throw new IllegalStateException("start"); }
    }

    public static final class PublicRecordingBridge extends PublicStableBridge {
        private final String id; private final List<String> events;
        PublicRecordingBridge(String id, List<String> events) { this.id = id; this.events = events; }
        @Override public void fence(long epoch) { events.add(id + ":fence:" + epoch); }
        @Override public void stopAcceptingAndDrain(long epoch) { events.add(id + ":drain:" + epoch); }
        @Override public void deactivate(long epoch) { events.add(id + ":deactivate:" + epoch); }
        @Override public void activate(long epoch) { events.add(id + ":activate:" + epoch); }
        @Override public void start(long epoch) { events.add(id + ":start:" + epoch); }
        @Override public boolean publish(long epoch) { events.add(id + ":publish:" + epoch); return true; }
    }

    private static class RecordingBridge implements PatchworkCoordinatorBridge {
        private final String id; private final List<String> events; private final boolean failStart; private final boolean failDrain;
        RecordingBridge(String id, List<String> events, boolean failStart, boolean failDrain) { this.id = id; this.events = events; this.failStart = failStart; this.failDrain = failDrain; }
        @Override public void fence(long epoch) { events.add(id + ":fence:" + epoch); }
        @Override public void stopAcceptingAndDrain(long epoch) { events.add(id + ":drain:" + epoch); if (failDrain) throw new IllegalStateException("drain"); }
        @Override public void deactivate(long epoch) { events.add(id + ":deactivate:" + epoch); }
        @Override public void activate(long epoch) { events.add(id + ":activate:" + epoch); }
        @Override public void start(long epoch) { events.add(id + ":start:" + epoch); if (failStart) throw new IllegalStateException("failed"); }
    }
}
