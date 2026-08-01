package com.alechilles.patchwork.embedded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.alechilles.patchwork.conditions.ConditionDocumentCache;
import com.alechilles.patchwork.conditions.ConditionSourceResolver;
import com.alechilles.patchwork.conditions.ModDataRootRegistry;
import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.alechilles.patchwork.engine.PatchMacroRegistry;
import com.alechilles.patchwork.reload.PatchReloadCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that each generation plan receives one fresh Hytale input snapshot and condition pass. */
final class GenerationPlanFactoryTest {
    @TempDir Path temporary;

    @Test void createsFreshSnapshotCacheAndResolverForEveryPlan() throws Exception {
        Path pack = Files.createDirectories(temporary.resolve("pack"));
        Files.createDirectories(pack.resolve("Server/Patchwork/Patches"));
        Files.createDirectories(pack.resolve("Server"));
        Files.writeString(pack.resolve("Server/Target.json"), "{\"value\":1}");
        Files.writeString(pack.resolve("Server/Patchwork/Patches/target.json"), """
                {"Id":"target","Target":"Server/Target.json","Operations":[{"Op":"Replace","Path":"/value","Value":2}]}
                """);
        var snapshots = new AtomicInteger();
        var caches = new ArrayList<ConditionDocumentCache>();
        var resolvers = new ArrayList<ConditionSourceResolver>();
        GenerationPlanFactory factory = new GenerationPlanFactory(new PatchMacroRegistry(), () -> {
            snapshots.incrementAndGet();
            return new HytaleRuntimeInputsSnapshotter.Inputs(List.of(PatchSource.directory("Test:Pack", 0, pack)),
                    Set.of("Test:Pack"), Map.of("Test:Pack", "1"), new ModDataRootRegistry(Map.of("Test:Mod", temporary)), List.of("Test:Pack"));
        }, () -> "1", () -> {
            ConditionDocumentCache cache = new ConditionDocumentCache();
            caches.add(cache);
            return cache;
        }, (roots, cache) -> {
            ConditionSourceResolver resolver = new ConditionSourceResolver(new PatchTargetResolver(), roots, cache);
            resolvers.add(resolver);
            return resolver;
        });

        var first = factory.createPlan();
        var second = factory.createPlan();

        assertEquals(2, snapshots.get());
        assertEquals(2, caches.size());
        assertEquals(2, resolvers.size());
        assertNotSame(caches.getFirst(), caches.getLast());
        assertNotSame(resolvers.getFirst(), resolvers.getLast());
        assertEquals(1, first.entries().size());
        assertEquals(1, second.entries().size());
    }

    @Test void eachAdmittedReloadInvokesTheFreshPlanFactory() {
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger caches = new AtomicInteger();
        AtomicInteger resolvers = new AtomicInteger();
        GenerationPlanFactory factory = new GenerationPlanFactory(new PatchMacroRegistry(), () -> {
            snapshots.incrementAndGet();
            return new HytaleRuntimeInputsSnapshotter.Inputs(List.of(), Set.of(), Map.of(), new ModDataRootRegistry(Map.of()), List.of());
        }, () -> "1", () -> { caches.incrementAndGet(); return new ConditionDocumentCache(); }, (roots, cache) -> {
            resolvers.incrementAndGet(); return new ConditionSourceResolver(new PatchTargetResolver(), roots, cache);
        });
        PatchworkAdministrationService service = new PatchworkAdministrationService(factory::createPlan,
                () -> (PatchworkAdministrationService.ReloadExecutor) request -> { request.generator().get(); return new PatchReloadCoordinator.ReloadOutcome(true, 4,
                        PatchReloadCoordinator.ManifestState.COMMITTED, List.of(), PatchReloadCoordinator.IntegrityState.RECONCILED, ""); },
                () -> pack -> { throw new AssertionError("self-test must not run"); });

        service.activate(4);
        service.reload().toCompletableFuture().join();
        service.reload().toCompletableFuture().join();

        assertEquals(2, snapshots.get());
        assertEquals(2, caches.get());
        assertEquals(2, resolvers.get());
    }

    @Test void rootMetadataUsesTheSameSingleSnapshotForInstalledAndIneligibleLegacyStates() {
        AtomicInteger snapshots = new AtomicInteger();
        var metadata = new ArrayList<GenerationPlanFactory.PassMetadata>();
        GenerationPlanFactory factory = new GenerationPlanFactory(new PatchMacroRegistry(), () -> {
            boolean installed = snapshots.getAndIncrement() == 0;
            return new HytaleRuntimeInputsSnapshotter.Inputs(List.of(), installed ? Set.of(com.alechilles.patchwork.discovery.PatchRoot.TAMEWORK_PLUGIN_ID) : Set.of(), Map.of(), new ModDataRootRegistry(Map.of()), List.of());
        }, () -> "1", ConditionDocumentCache::new, (roots, cache) -> new ConditionSourceResolver(new PatchTargetResolver(), roots, cache), metadata::add);
        factory.createPlan(); factory.createPlan();
        assertEquals(2, snapshots.get());
        assertEquals("Server/Patchwork/Patches (active)", metadata.getFirst().neutralRoot());
        assertEquals(List.of("Server/Tamework/Patches (active)"), metadata.getFirst().legacyRoots());
        assertEquals(List.of("Server/Tamework/Patches (ineligible)"), metadata.getLast().legacyRoots());
    }
}
