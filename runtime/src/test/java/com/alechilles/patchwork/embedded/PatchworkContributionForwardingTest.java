package com.alechilles.patchwork.embedded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.alechilles.patchwork.reload.HytalePatchTargetAdapter;
import com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry;
import com.alechilles.patchwork.reload.PatchReloadCoordinator;
import com.alechilles.patchwork.reload.PatchTargetClassifier;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatchworkContributionForwardingTest {
    @Test void electedReplacementReplaysExistingContributionWithoutReregistration() throws Exception {
        Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try {
            PatchworkRuntimeHost first = new PatchworkRuntimeHost(Path.of("first"), () -> { });
            String firstToken = PatchworkCoordinatorRegistry.register(Map.of("providerId", "first", "origin", "EMBEDDED", "runtimeVersion", "1.0.0", "coordinatorAbi", 1, "providerPluginId", "first", "providerPluginVersion", "1", "sourceJarPath", Path.of("first.jar"), "providerDataRoot", Path.of("first"), "bridge", first));
            PatchworkCoordinatorRegistry.registerContribution(Map.of("hostPluginIdentifier", "Host", "contributionVersion", "1", "macroIds", List.of(), "adapterIds", List.of("replayed"), "bridge", new EmbeddedPatchworkBootstrap.HostBridge(contribution("replayed", request -> CompletableFuture.completedFuture(new PatchworkReloadResult("replayed", List.of("Server/Test.json"), List.of(), List.of()))))));
            PatchworkRuntimeHost newer = new PatchworkRuntimeHost(Path.of("newer"), () -> { });
            String newerToken = PatchworkCoordinatorRegistry.register(Map.of("providerId", "newer", "origin", "EMBEDDED", "runtimeVersion", "2.0.0", "coordinatorAbi", 1, "providerPluginId", "newer", "providerPluginVersion", "1", "sourceJarPath", Path.of("newer.jar"), "providerDataRoot", Path.of("newer"), "bridge", newer));
            var target = new HytalePatchTargetAdapter.ReloadTarget("pass", 2L, "Server/Test.json", "hash", false, PatchTargetClassifier.Family.CUSTOM);
            assertTrue(newer.targetAdapter().supports(target)); assertTrue(newer.targetAdapter().reload(target).accepted());
            PatchworkCoordinatorRegistry.unregister(newerToken); PatchworkCoordinatorRegistry.unregister(firstToken);
        } finally { if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original); }
    }
    @Test void registryRejectsDuplicateContributionMetadataWithoutPartialAdmission() {
        Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try {
            PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("generated"), () -> { });
            String provider = PatchworkCoordinatorRegistry.register(Map.of("providerId", "owner", "origin", "EMBEDDED", "runtimeVersion", "1.0.0", "coordinatorAbi", 1, "providerPluginId", "owner", "providerPluginVersion", "1", "sourceJarPath", Path.of("owner.jar"), "providerDataRoot", Path.of("owner"), "bridge", host));
            String first = PatchworkCoordinatorRegistry.registerContribution(Map.of("hostPluginIdentifier", "Host", "contributionVersion", "1", "macroIds", List.of("macro"), "adapterIds", List.of("adapter"), "bridge", new EmbeddedPatchworkBootstrap.HostBridge(contribution("adapter", ignored -> CompletableFuture.completedFuture(new PatchworkReloadResult("adapter", List.of(), List.of(), List.of()))))));
            assertThrows(IllegalArgumentException.class, () -> PatchworkCoordinatorRegistry.registerContribution(Map.of("hostPluginIdentifier", "Host", "contributionVersion", "1", "macroIds", List.of("macro"), "adapterIds", List.of("other"), "bridge", new EmbeddedPatchworkBootstrap.HostBridge(contribution("other", ignored -> CompletableFuture.completedFuture(new PatchworkReloadResult("other", List.of(), List.of(), List.of())))))));
            assertTrue(PatchworkCoordinatorRegistry.unregisterContribution(first));
            PatchworkCoordinatorRegistry.unregister(provider);
        } finally { if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original); }
    }
    @Test void hostBridgeSnapshotsStatefulContributionGettersOnce() {
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger idReads = new java.util.concurrent.atomic.AtomicInteger();
        List<PatchworkMacroProvider> providers = new java.util.ArrayList<>(List.of(new PatchworkMacroProvider() {
            public String macroId() { idReads.incrementAndGet(); return "one"; }
            public JsonArray expand(JsonObject operation) { return new JsonArray(); }
        }));
        PatchworkHostContribution stateful = new PatchworkHostContribution() {
            public String hostPluginIdentifier() { reads.incrementAndGet(); return "Host"; }
            public String contributionVersion() { reads.incrementAndGet(); return "1"; }
            public List<PatchworkMacroProvider> macroProviders() { reads.incrementAndGet(); return providers; }
            public List<PatchworkTargetAdapter> targetAdapters() { reads.incrementAndGet(); return List.of(); }
        };
        EmbeddedPatchworkBootstrap.HostBridge bridge = new EmbeddedPatchworkBootstrap.HostBridge(stateful);
        providers.clear();
        bridge.expand("one", "{}");
        assertThrows(IllegalArgumentException.class, () -> bridge.expand("changed", "{}"));
        assertEquals(4, reads.get());
        assertEquals(1, idReads.get());
    }
    @Test void contributionContractsDefensivelyCopyRequests() {
        PatchworkReloadRequest request = new PatchworkReloadRequest(4L, List.of(new PatchworkTargetExpectation("Server/Test.json", "abc", false)));
        assertEquals(1, request.targets().size());
    }

    @Test void rejectsBlankContributionMetadata() {
        assertThrows(IllegalArgumentException.class, () -> PatchworkHostContribution.validate(" ", "1", List.of(), List.of()));
    }

    @Test void electedHostInvokesNarrowAdapterAndMapsStructuredResult() throws Exception {
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("generated"), () -> { });
        host.activate(4L);
        EmbeddedPatchworkBootstrap.HostBridge bridge = new EmbeddedPatchworkBootstrap.HostBridge(contribution("reloadable", request ->
                CompletableFuture.completedFuture(new PatchworkReloadResult("reloadable", List.of("Server/Test.json"), List.of(), List.of()))));
        host.replayContributions(4L, List.of(Map.of("contributionToken", "one", "hostPluginIdentifier", "Example:Host", "contributionVersion", "1.0.0", "macroIds", List.of(), "adapterIds", List.of("reloadable"), "bridge", bridge)));
        HytalePatchTargetAdapter adapter = host.targetAdapter();
        HytalePatchTargetAdapter.ReloadTarget target = new HytalePatchTargetAdapter.ReloadTarget("pass", 4L, "Server/Test.json", "hash", false, PatchTargetClassifier.Family.CUSTOM);
        assertEquals("patchwork-host-contributions", adapter.adapterId());
        assertEquals(true, adapter.supports(target));
        assertEquals(true, adapter.reload(target).accepted());
    }

    @Test void hostRejectsMismatchedStructuredAdapterResult() throws Exception {
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("generated"), () -> { }); host.activate(1L);
        EmbeddedPatchworkBootstrap.HostBridge bridge = new EmbeddedPatchworkBootstrap.HostBridge(contribution("expected", request -> CompletableFuture.completedFuture(new PatchworkReloadResult("wrong", List.of("Server/Test.json"), List.of(), List.of()))));
        host.replayContributions(1L, List.of(Map.of("contributionToken", "one", "hostPluginIdentifier", "Host", "contributionVersion", "1", "macroIds", List.of(), "adapterIds", List.of("expected"), "bridge", bridge)));
        var target = new HytalePatchTargetAdapter.ReloadTarget("pass", 1L, "Server/Test.json", "hash", false, PatchTargetClassifier.Family.CUSTOM);
        assertFalse(host.targetAdapter().reload(target).accepted());
    }

    @Test void fencedHostRejectsAdapterCalls() {
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("generated"), () -> { });
        host.activate(1L);
        host.fence(1L);
        HytalePatchTargetAdapter.ReloadTarget target = new HytalePatchTargetAdapter.ReloadTarget("pass", 1L, "Server/Test.json", "hash", false, PatchTargetClassifier.Family.CUSTOM);
        assertEquals(false, host.targetAdapter().supports(target));
    }

    @Test void contributionValidationRejectsBlankNestedIds() {
        PatchworkHostContribution invalid = new PatchworkHostContribution() {
            public String hostPluginIdentifier() { return "Example:Host"; }
            public String contributionVersion() { return "1"; }
            public List<PatchworkMacroProvider> macroProviders() { return List.of(new PatchworkMacroProvider() {
                public String macroId() { return " "; }
                public JsonArray expand(JsonObject operation) { return new JsonArray(); }
            }); }
            public List<PatchworkTargetAdapter> targetAdapters() { return List.of(); }
        };
        assertThrows(IllegalArgumentException.class, () -> PatchworkHostContribution.validate(invalid.hostPluginIdentifier(), invalid.contributionVersion(), invalid.macroProviders(), invalid.targetAdapters()));
    }

    @Test void unregisterContributionDrainsAnInFlightAsyncReloadBeforeRemovingItsSnapshot() throws Exception {
        Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try {
            CompletableFuture<PatchworkReloadResult> pending = new CompletableFuture<>(); CountDownLatch entered = new CountDownLatch(1);
            PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("generated"), () -> { });
            String provider = PatchworkCoordinatorRegistry.register(Map.of("providerId", "async", "origin", "EMBEDDED", "runtimeVersion", "1.0.0", "coordinatorAbi", 1, "providerPluginId", "async", "providerPluginVersion", "1", "sourceJarPath", Path.of("async.jar"), "providerDataRoot", Path.of("async"), "bridge", host));
            EmbeddedPatchworkBootstrap.HostBridge bridge = new EmbeddedPatchworkBootstrap.HostBridge(contribution("async", ignored -> { entered.countDown(); return pending; }));
            String contribution = PatchworkCoordinatorRegistry.registerContribution(Map.of("hostPluginIdentifier", "Host", "contributionVersion", "1", "macroIds", List.of(), "adapterIds", List.of("async"), "bridge", bridge));
            HytalePatchTargetAdapter.ReloadTarget target = new HytalePatchTargetAdapter.ReloadTarget("reload", 1L, "Server/Test.json", "hash", false, PatchTargetClassifier.Family.CUSTOM);
            Thread reload = new Thread(() -> { try { host.targetAdapter().reload(target); } catch (Exception ignored) { } });
            reload.start(); assertTrue(entered.await(1, TimeUnit.SECONDS));
            Thread unregister = new Thread(() -> PatchworkCoordinatorRegistry.unregisterContribution(contribution));
            unregister.start(); Thread.sleep(100); assertTrue(unregister.isAlive(), "unregister must wait for the pending stage");
            pending.complete(new PatchworkReloadResult("async", List.of("Server/Test.json"), List.of(), List.of()));
            reload.join(1500); unregister.join(1500); assertFalse(unregister.isAlive());
            assertFalse(PatchworkCoordinatorRegistry.unregisterContribution(contribution));
            PatchworkCoordinatorRegistry.unregister(provider);
        } finally { if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original); }
    }

    @Test void realCoordinatorUsesTheHostTrackerAndRejectsWrongObservationCorrelation() throws Exception {
        Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try {
            Path root = java.nio.file.Files.createTempDirectory("patchwork-host"); PatchworkRuntimeHost host = new PatchworkRuntimeHost(root, () -> { });
            String provider = PatchworkCoordinatorRegistry.register(Map.of("providerId", "coordinator", "origin", "EMBEDDED", "runtimeVersion", "1.0.0", "coordinatorAbi", 1, "providerPluginId", "coordinator", "providerPluginVersion", "1", "sourceJarPath", Path.of("coordinator.jar"), "providerDataRoot", root, "bridge", host));
            EmbeddedPatchworkBootstrap.HostBridge bridge = new EmbeddedPatchworkBootstrap.HostBridge(contribution("coordinator", request -> {
                PatchworkCoordinatorRegistry.recordObservation(Map.of("epoch", request.epoch() + 1, "adapterId", "coordinator", "target", request.targets().getFirst().target(), "expectedHash", request.targets().getFirst().expectedHash(), "outcome", "LOADED"));
                PatchworkCoordinatorRegistry.recordObservation(Map.of("epoch", request.epoch(), "adapterId", "coordinator", "target", request.targets().getFirst().target(), "expectedHash", request.targets().getFirst().expectedHash(), "outcome", "LOADED"));
                return CompletableFuture.completedFuture(new PatchworkReloadResult("coordinator", List.of(request.targets().getFirst().target()), List.of(), List.of()));
            }));
            PatchworkCoordinatorRegistry.registerContribution(Map.of("hostPluginIdentifier", "Host", "contributionVersion", "1", "macroIds", List.of(), "adapterIds", List.of("coordinator"), "bridge", bridge));
            var coordinator = host.reloadCoordinator(java.time.Duration.ofSeconds(1)); coordinator.activate(1L);
            var outcome = coordinator.reload(PatchReloadCoordinator.ReloadRequest.authorized(() -> new PatchReloadCoordinator.ReloadPlan("{}".getBytes(), List.of(new PatchReloadCoordinator.TargetUpdate("Server/Test.json", "{}".getBytes())))));
            assertEquals(PatchReloadCoordinator.TargetState.ADAPTER_RELOADED, outcome.targets().getFirst().state());
            assertFalse(PatchworkCoordinatorRegistry.recordObservation(Map.of("epoch", 1L, "adapterId", "coordinator", "target", "Server/Test.json", "expectedHash", "wrong", "outcome", "LOADED")));
            PatchworkCoordinatorRegistry.unregister(provider);
        } finally { if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original); }
    }

    private static PatchworkHostContribution contribution(String id, java.util.function.Function<PatchworkReloadRequest, java.util.concurrent.CompletionStage<PatchworkReloadResult>> reload) {
        return new PatchworkHostContribution() {
            public String hostPluginIdentifier() { return "Example:Host"; }
            public String contributionVersion() { return "1.0.0"; }
            public List<PatchworkMacroProvider> macroProviders() { return List.of(); }
            public List<PatchworkTargetAdapter> targetAdapters() {
                return List.of(new PatchworkTargetAdapter() {
                    public String adapterId() { return id; }
                    public boolean supports(String target) { return target.equals("Server/Test.json"); }
                    public java.util.concurrent.CompletionStage<PatchworkReloadResult> reload(PatchworkReloadRequest request) { return reload.apply(request); }
                });
            }
        };
    }
}
