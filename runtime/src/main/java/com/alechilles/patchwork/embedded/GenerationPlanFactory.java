package com.alechilles.patchwork.embedded;

import com.alechilles.patchwork.conditions.ConditionDocumentCache;
import com.alechilles.patchwork.conditions.ConditionSourceResolver;
import com.alechilles.patchwork.conditions.ModDataRootRegistry;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.alechilles.patchwork.discovery.PatchRoot;
import com.alechilles.patchwork.engine.PatchMacroRegistry;
import com.alechilles.patchwork.generation.GenerationAssetSnapshot;
import com.alechilles.patchwork.generation.PatchGenerationService;
import com.google.gson.JsonElement;
import com.hypixel.hytale.common.util.java.ManifestUtil;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Creates one isolated generation plan from one current runtime-input snapshot. */
final class GenerationPlanFactory {
    private final PatchMacroRegistry macros;
    private final Supplier<HytaleRuntimeInputsSnapshotter.Inputs> inputs;
    private final Supplier<String> serverVersion;
    private final Supplier<ConditionDocumentCache> caches;
    private final BiFunction<ModDataRootRegistry, ConditionDocumentCache, ConditionSourceResolver> resolvers;
    private final Consumer<PassMetadata> passMetadata;
    private final Supplier<Map<String, JsonElement>> settings;

    GenerationPlanFactory(PatchMacroRegistry macros, Supplier<HytaleRuntimeInputsSnapshotter.Inputs> inputs) {
        this(macros, inputs, ManifestUtil::getVersion, ConditionDocumentCache::new,
                (roots, cache) -> new ConditionSourceResolver(new PatchTargetResolver(), roots, cache), ignored -> { });
    }

    GenerationPlanFactory(PatchMacroRegistry macros, Supplier<HytaleRuntimeInputsSnapshotter.Inputs> inputs,
                          Supplier<String> serverVersion, Supplier<ConditionDocumentCache> caches,
                          BiFunction<ModDataRootRegistry, ConditionDocumentCache, ConditionSourceResolver> resolvers) {
        this(macros, inputs, serverVersion, caches, resolvers, ignored -> { });
    }

    GenerationPlanFactory(PatchMacroRegistry macros, Supplier<HytaleRuntimeInputsSnapshotter.Inputs> inputs,
                          Supplier<String> serverVersion, Supplier<ConditionDocumentCache> caches,
                          BiFunction<ModDataRootRegistry, ConditionDocumentCache, ConditionSourceResolver> resolvers,
                          Consumer<PassMetadata> passMetadata) {
        this(macros, inputs, serverVersion, caches, resolvers, passMetadata, Map::of);
    }

    GenerationPlanFactory(PatchMacroRegistry macros, Supplier<HytaleRuntimeInputsSnapshotter.Inputs> inputs,
                          Supplier<String> serverVersion, Supplier<ConditionDocumentCache> caches,
                          BiFunction<ModDataRootRegistry, ConditionDocumentCache, ConditionSourceResolver> resolvers,
                          Consumer<PassMetadata> passMetadata, Supplier<Map<String, JsonElement>> settings) {
        this.macros = Objects.requireNonNull(macros, "macros");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.serverVersion = Objects.requireNonNull(serverVersion, "serverVersion");
        this.caches = Objects.requireNonNull(caches, "caches");
        this.resolvers = Objects.requireNonNull(resolvers, "resolvers");
        this.passMetadata = Objects.requireNonNull(passMetadata, "passMetadata");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Takes exactly one input and setting snapshot and creates a cache/resolver pair that cannot escape this pass. */
    PatchGenerationService.GenerationPlan createPlan() {
        HytaleRuntimeInputsSnapshotter.Inputs snapshot = Objects.requireNonNull(inputs.get(), "runtime input snapshot");
        GenerationAssetSnapshot assets = GenerationAssetSnapshot.capture(snapshot.sources());
        Map<String, JsonElement> settingSnapshot = Objects.requireNonNullElse(settings.get(), Map.of());
        passMetadata.accept(PassMetadata.from(snapshot));
        ConditionDocumentCache cache = Objects.requireNonNull(caches.get(), "condition document cache");
        ConditionSourceResolver resolver = Objects.requireNonNull(resolvers.apply(snapshot.modDataRoots(), cache), "condition source resolver")
                .withAssets(assets);
        String version = serverVersion.get();
        if (version == null || version.isBlank()) throw new IllegalStateException("Hytale server version is unavailable from ManifestUtil.getVersion().");
        return new PatchGenerationService(macros).generate(new PatchGenerationService.GenerationRequest(
                assets, snapshot.installedIds(), snapshot.versions(), version, resolver, Map.of(), settingSnapshot));
    }

    /** Safe, immutable root state derived only from the pass's captured installed-plugin identifiers. */
    record PassMetadata(String neutralRoot, List<String> legacyRoots) {
        static PassMetadata from(HytaleRuntimeInputsSnapshotter.Inputs inputs) {
            boolean tamework = inputs.installedIds().contains(PatchRoot.TAMEWORK_PLUGIN_ID);
            return new PassMetadata("Server/Patchwork/Patches (active)", List.of("Server/Tamework/Patches (" + (tamework ? "active" : "ineligible") + ")"));
        }
    }
}
