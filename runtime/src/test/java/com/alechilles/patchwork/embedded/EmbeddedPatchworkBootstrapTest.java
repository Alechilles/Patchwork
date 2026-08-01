package com.alechilles.patchwork.embedded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry;
import org.junit.jupiter.api.Test;

class EmbeddedPatchworkBootstrapTest {
    @Test void closingOlderServicePreservesNewerWinnerAndRejectsReuse() {
        Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try {
            EmbeddedPatchworkService old = EmbeddedPatchworkBootstrap.createEmbeddedService("old", "1.0.0", Path.of("old.jar"), Path.of("build/old/data"), () -> { });
            EmbeddedPatchworkService newer = EmbeddedPatchworkBootstrap.createEmbeddedService("new", "2.0.0", Path.of("new.jar"), Path.of("build/new/data"), () -> { });
            old.start(); newer.start(); old.close(); old.close();
            assertEquals("new", PatchworkCoordinatorRegistry.activeProviderId());
            assertThrows(IllegalStateException.class, old::start);
            assertThrows(IllegalStateException.class, () -> old.registerContribution(new PatchworkHostContribution() { public String hostPluginIdentifier(){return "H";} public String contributionVersion(){return "1";} public java.util.List<PatchworkMacroProvider> macroProviders(){return java.util.List.of();} public java.util.List<PatchworkTargetAdapter> targetAdapters(){return java.util.List.of();} }));
            newer.close();
        } finally { if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original); }
    }
    @Test void rejectsMissingRuntimeVersion() {
        assertThrows(IllegalStateException.class, () -> EmbeddedPatchworkBootstrap.requireRuntimeVersion(null, null));
    }

    @Test void acceptsMavenVersionBeforePackageVersion() {
        assertEquals("1.2.3", EmbeddedPatchworkBootstrap.requireRuntimeVersion("1.2.3", "9.9.9"));
    }

    @Test void electedStartRegistersItsEarlyLoadCallbackOnlyOnce() {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("build/test-generated"),
                new PatchworkRuntimeHost.EarlyLoadRegistrar() {
                    public PatchworkRuntimeHost.EarlyLoadRegistration register(long epoch, java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent> callback) { registrations.incrementAndGet(); return () -> { }; }
                    public void execute(long epoch, com.alechilles.patchwork.engine.PatchMacroRegistry macros, com.hypixel.hytale.server.core.asset.LoadAssetEvent event, PatchworkRuntimeHost.EpochActionGate actionGate) { runs.incrementAndGet(); }
                });

        host.activate(7L);
        host.start(7L);
        host.start(7L);

        assertEquals(1, registrations.get());
        host.runRegisteredEarlyLoadForTest();
        assertEquals(1, runs.get());
    }

    @Test void fencedEarlyLoadCallbackIsANoOp() {
        AtomicInteger runs = new AtomicInteger();
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("build/test-generated"),
                new PatchworkRuntimeHost.EarlyLoadRegistrar() {
                    public PatchworkRuntimeHost.EarlyLoadRegistration register(long epoch, java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent> callback) { return () -> { }; }
                    public void execute(long epoch, com.alechilles.patchwork.engine.PatchMacroRegistry macros, com.hypixel.hytale.server.core.asset.LoadAssetEvent event, PatchworkRuntimeHost.EpochActionGate actionGate) { runs.incrementAndGet(); }
                });

        host.activate(7L);
        host.start(7L);
        host.fence(7L);
        host.runRegisteredEarlyLoadForTest();

        assertEquals(0, runs.get());
    }

    @Test void earlyLoadPublicationDoesNotRunAfterItsEpochIsFenced() throws Exception {
        AtomicInteger publications = new AtomicInteger();
        CountDownLatch readyToPublish = new CountDownLatch(1);
        CountDownLatch continuePublication = new CountDownLatch(1);
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("build/test-generated"),
                new PatchworkRuntimeHost.EarlyLoadRegistrar() {
                    public PatchworkRuntimeHost.EarlyLoadRegistration register(long epoch, java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent> callback) { return () -> { }; }
                    public void execute(long epoch, com.alechilles.patchwork.engine.PatchMacroRegistry macros, com.hypixel.hytale.server.core.asset.LoadAssetEvent event, PatchworkRuntimeHost.EpochActionGate actionGate) {
                        readyToPublish.countDown();
                        try { assertTrue(continuePublication.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                        actionGate.execute(publications::incrementAndGet);
                    }
                });

        host.activate(7L);
        host.start(7L);
        Thread callback = new Thread(host::runRegisteredEarlyLoadForTest);
        callback.start();
        assertTrue(readyToPublish.await(5, TimeUnit.SECONDS));
        host.fence(7L);
        continuePublication.countDown();
        callback.join(5_000L);

        assertFalse(callback.isAlive());
        assertEquals(0, publications.get());
    }

    @Test void deactivateUnregistersTheEarlyLoadCallbackBeforeTheNextActivation() {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger unregistrations = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        List<java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent>> callbacks = new ArrayList<>();
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("build/test-generated"),
                new PatchworkRuntimeHost.EarlyLoadRegistrar() {
                    public PatchworkRuntimeHost.EarlyLoadRegistration register(long epoch, java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent> callback) {
                        registrations.incrementAndGet();
                        callbacks.add(callback);
                        return unregistrations::incrementAndGet;
                    }
                    public void execute(long epoch, com.alechilles.patchwork.engine.PatchMacroRegistry macros, com.hypixel.hytale.server.core.asset.LoadAssetEvent event, PatchworkRuntimeHost.EpochActionGate actionGate) { executions.incrementAndGet(); }
                });

        host.activate(1L); host.start(1L); host.deactivate(1L);
        host.activate(2L); host.start(2L); host.deactivate(2L);
        host.activate(3L); host.start(3L);
        callbacks.getFirst().accept(null);
        host.runRegisteredEarlyLoadForTest();

        assertEquals(3, registrations.get());
        assertEquals(2, unregistrations.get());
        assertEquals(1, executions.get());
    }

    @Test void failedEarlyLoadUnregistrationRetainsTheHandleForRetry() {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger unregisterAttempts = new AtomicInteger();
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("build/test-generated"),
                new PatchworkRuntimeHost.EarlyLoadRegistrar() {
                    public PatchworkRuntimeHost.EarlyLoadRegistration register(long epoch, java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent> callback) {
                        registrations.incrementAndGet();
                        return () -> {
                            if (unregisterAttempts.incrementAndGet() == 1) throw new IllegalStateException("transient unregister failure");
                        };
                    }
                });

        host.activate(1L);
        host.start(1L);

        assertThrows(IllegalStateException.class, () -> host.deactivate(1L));
        host.deactivate(1L);
        host.activate(2L);
        host.start(2L);

        assertEquals(2, unregisterAttempts.get());
        assertEquals(2, registrations.get());
    }
}
