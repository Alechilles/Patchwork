package com.alechilles.patchwork.embedded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
                    public void register(long epoch, java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent> callback) { registrations.incrementAndGet(); }
                    public void execute(long epoch, com.alechilles.patchwork.engine.PatchMacroRegistry macros, com.hypixel.hytale.server.core.asset.LoadAssetEvent event) { runs.incrementAndGet(); }
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
                    public void register(long epoch, java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent> callback) { }
                    public void execute(long epoch, com.alechilles.patchwork.engine.PatchMacroRegistry macros, com.hypixel.hytale.server.core.asset.LoadAssetEvent event) { runs.incrementAndGet(); }
                });

        host.activate(7L);
        host.start(7L);
        host.fence(7L);
        host.runRegisteredEarlyLoadForTest();

        assertEquals(0, runs.get());
    }
}
