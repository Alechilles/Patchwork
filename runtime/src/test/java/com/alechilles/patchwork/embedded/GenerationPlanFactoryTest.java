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

    @Test void rejectsOnlyTheTargetChangedBeforeResolverConstruction() throws Exception {
        Path pack = Files.createDirectories(temporary.resolve("capture-order"));
        Files.createDirectories(pack.resolve("Server/Patchwork/Patches"));
        Files.createDirectories(pack.resolve("Server"));
        Files.writeString(pack.resolve("Server/Target.json"), "{\"value\":1}");
        Files.writeString(pack.resolve("Server/Patchwork/Patches/target.json"),
                "{\"Id\":\"target\",\"Target\":\"Server/Target.json\",\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":2}]}");
        GenerationPlanFactory factory = new GenerationPlanFactory(new PatchMacroRegistry(), () ->
                new HytaleRuntimeInputsSnapshotter.Inputs(List.of(PatchSource.directory("Test:Pack", 0, pack)),
                        Set.of(), Map.of(), new ModDataRootRegistry(Map.of()), List.of("Test:Pack")),
                () -> "1", ConditionDocumentCache::new, (roots, cache) -> {
                    try {
                        Files.writeString(pack.resolve("Server/Target.json"), "not json");
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    return new ConditionSourceResolver(new PatchTargetResolver(), roots, cache);
                });

        var plan = factory.createPlan();

        assertEquals(List.of(), plan.entries());
        assertEquals(true, plan.status().rejectedTargets().containsKey("Server/Target.json"));
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

    @Test void contributionSettingsCrossTheHostBridgeIntoAGenerationPass() throws Exception {
        // Catches host setting values not reaching the elected runtime's pass, and one failing
        // contribution breaking the pass instead of contributing nothing.
        Path pack = Files.createDirectories(temporary.resolve("settings-pack"));
        Files.createDirectories(pack.resolve("Server/Patchwork/Patches"));
        Files.writeString(pack.resolve("Server/Target.json"), "{\"value\":1,\"wild\":true}");
        Files.writeString(pack.resolve("Server/Patchwork/Patches/target.json"), """
                {"Id":"target","Target":"Server/Target.json","When":{"Setting":{"Id":"AH_WildDragons","Is":false}},"Operations":[
                  {"Op":"Replace","Path":"/value","Value":{"$Setting":"AH_DragonWeight"}},
                  {"Op":"Replace","Path":"/wild","Value":{"$Setting":"AH_WildDragons"}}
                ]}
                """);
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(temporary.resolve("generated"), () -> { });
        host.activate(1L);
        host.replayContributions(1L, List.of(
                contribution("a-failing", () -> { throw new IllegalStateException("settings unavailable"); }),
                contribution("b-settings", () -> Map.of("AH_WildDragons", new com.google.gson.JsonPrimitive(false),
                        "AH_DragonWeight", new com.google.gson.JsonPrimitive(3)))));
        GenerationPlanFactory factory = new GenerationPlanFactory(new PatchMacroRegistry(), () ->
                new HytaleRuntimeInputsSnapshotter.Inputs(List.of(PatchSource.directory("Test:Pack", 0, pack)),
                        Set.of(), Map.of(), new ModDataRootRegistry(Map.of()), List.of("Test:Pack")),
                () -> "1", ConditionDocumentCache::new, (roots, cache) -> new ConditionSourceResolver(new PatchTargetResolver(), roots, cache),
                ignored -> { }, host::settingValues);

        var plan = factory.createPlan();

        assertEquals(1, plan.entries().size());
        assertEquals("{\"value\":3,\"wild\":false}", new String(plan.entries().getFirst().bytes(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Map<String, ?> contribution(String host, java.util.function.Supplier<Map<String, com.google.gson.JsonElement>> settings) {
        PatchworkHostContribution contribution = new PatchworkHostContribution() {
            public String hostPluginIdentifier() { return host; }
            public String contributionVersion() { return "1"; }
            public List<PatchworkMacroProvider> macroProviders() { return List.of(); }
            public List<PatchworkTargetAdapter> targetAdapters() { return List.of(); }
            @Override public Map<String, com.google.gson.JsonElement> settingValues() { return settings.get(); }
        };
        return Map.of("contributionToken", host, "hostPluginIdentifier", host, "contributionVersion", "1",
                "macroIds", List.of(), "adapterIds", List.of(), "bridge", new EmbeddedPatchworkBootstrap.HostBridge(contribution));
    }
}
