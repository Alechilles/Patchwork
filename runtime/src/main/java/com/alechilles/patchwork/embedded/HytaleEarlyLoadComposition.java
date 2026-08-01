package com.alechilles.patchwork.embedded;

import com.alechilles.patchwork.conditions.ConditionDocumentCache;
import com.alechilles.patchwork.conditions.ConditionSourceResolver;
import com.alechilles.patchwork.conditions.ModDataRootRegistry;
import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.alechilles.patchwork.engine.PatchMacroRegistry;
import com.alechilles.patchwork.generation.GeneratedPackLayout;
import com.alechilles.patchwork.generation.PatchGenerationService;
import com.alechilles.patchwork.generation.PatchworkEarlyLoadHook;
import com.alechilles.patchwork.generation.StartupPackPublisher;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;
import com.hypixel.hytale.common.util.java.ManifestUtil;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.LoadAssetEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Registers and executes the elected runtime's single early asset-load generation pass. */
final class HytaleEarlyLoadComposition implements PatchworkRuntimeHost.EarlyLoadRegistrar {
    private static final System.Logger LOG = System.getLogger(HytaleEarlyLoadComposition.class.getName());
    private final JavaPlugin plugin;
    private final GeneratedPackLayout layout;

    HytaleEarlyLoadComposition(JavaPlugin plugin, GeneratedPackLayout layout) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    @Override public void register(long epoch, Consumer<LoadAssetEvent> callback) {
        plugin.getEventRegistry().register(PatchworkEarlyLoadHook.PRIORITY, LoadAssetEvent.class, callback);
    }

    @Override public void execute(long epoch, PatchMacroRegistry macros, LoadAssetEvent event) {
        try {
            Inputs inputs = snapshotInputs();
            ConditionSourceResolver resolver = new ConditionSourceResolver(new PatchTargetResolver(), inputs.modDataRoots(), new ConditionDocumentCache());
            String serverVersion = ManifestUtil.getVersion();
            if (serverVersion == null || serverVersion.isBlank()) throw new IllegalStateException("Hytale server version is unavailable from ManifestUtil.getVersion().");
            PatchGenerationService.GenerationPlan plan = new PatchGenerationService(macros).generate(
                    new PatchGenerationService.GenerationRequest(inputs.sources(), inputs.installedIds(), inputs.versions(), serverVersion, resolver));
            StartupPackPublisher publisher = new StartupPackPublisher(layout, new RuntimePackRegistrar(inputs.sourcePackIds()));
            StartupPackPublisher.Publication publication = publisher.publish(plan);
            if (!publication.published()) fail(event, "Patchwork startup generation failed: " + publication.diagnostic());
            else if (!plan.status().scanFailures().isEmpty() || !plan.status().rejectedTargets().isEmpty()) {
                fail(event, "Patchwork generated valid targets with recoverable diagnostics: " + plan.status().scanFailures().size() + " scan failure(s), " + plan.status().rejectedTargets().size() + " rejected target(s).");
            }
        } catch (Exception failure) {
            String message = "Patchwork startup generation failed: " + detail(failure);
            LOG.log(System.Logger.Level.ERROR, message, failure);
            fail(event, message);
        }
    }

    private Inputs snapshotInputs() {
        List<PatchSource> sources = new ArrayList<>();
        List<String> sourceIds = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Map<String, String> versions = new LinkedHashMap<>();
        int order = 0;
        for (AssetPack pack : AssetModule.get().getAssetPacks()) {
            String id = pack.getName();
            sourceIds.add(id);
            ids.add(id);
            if (pack.getManifest() != null && pack.getManifest().getVersion() != null) versions.put(id, pack.getManifest().getVersion().toString());
            boolean directory = pack.getFileSystem() == null || pack.getFileSystem().equals(FileSystems.getDefault());
            Path root = directory ? pack.getRoot() : pack.getPackLocation();
            if (!directory && (root == null || !Files.isRegularFile(root))) {
                throw new IllegalStateException("Asset pack " + id + " has no readable archive pack location.");
            }
            sources.add(directory ? PatchSource.directory(id, order++, root) : PatchSource.archive(id, order++, root));
        }
        PluginManager manager = PluginManager.get();
        for (PluginBase loaded : manager.getPlugins()) {
            String id = loaded.getIdentifier().toString();
            ids.add(id);
            versions.put(id, loaded.getManifest().getVersion().toString());
        }
        return new Inputs(List.copyOf(sources), Set.copyOf(ids), Map.copyOf(versions), ModDataRootRegistry.fromPluginManager(manager), List.copyOf(sourceIds));
    }

    private void fail(LoadAssetEvent event, String message) {
        if (event != null) event.failed(false, message);
        LOG.log(System.Logger.Level.ERROR, message);
    }

    private static String detail(Exception failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank() ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /** Registers the staged generated pack only after the publisher has verified and activated it. */
    private final class RuntimePackRegistrar implements StartupPackPublisher.PackRegistrar {
        private final List<String> sourcePackIds;
        private RuntimePackRegistrar(List<String> sourcePackIds) { this.sourcePackIds = sourcePackIds; }
        @Override public void register(String ignored) { throw new UnsupportedOperationException("Generation registration requires its source dependency snapshot."); }
        @Override public StartupPackPublisher.RegistrationAttempt prepare(String packId, PatchGenerationService.GenerationPlan plan) {
            PluginManifest manifest = generatedManifest(sourcePackIds);
            return new StartupPackPublisher.RegistrationAttempt() {
                private boolean registered;
                @Override public void commit() {
                    AssetPack existing = AssetModule.get().getAssetPack(packId);
                    if (existing != null) {
                        if (existing.getRoot().toAbsolutePath().normalize().equals(layout.generatedRoot())) return;
                        throw new IllegalStateException("A different asset pack already owns " + packId + ".");
                    }
                    if (!AssetModule.get().registerPack(packId, layout.generatedRoot(), manifest, AssetPack.PackSource.RUNTIME)) {
                        throw new IllegalStateException("Hytale rejected generated Patchwork pack registration.");
                    }
                    registered = true;
                }
                @Override public void rollback() { if (registered) AssetModule.get().unregisterPack(packId); }
            };
        }
    }

    private PluginManifest generatedManifest(List<String> sourcePackIds) {
        PluginManifest manifest = new PluginManifest();
        manifest.setGroup("Alechilles");
        manifest.setName("Patchwork_GeneratedPatches");
        manifest.setVersion(Semver.fromString("1.0.0"));
        manifest.setDescription("Generated Patchwork asset pack.");
        manifest.setAuthors(List.of());
        manifest.setWebsite(null);
        manifest.setServerVersion(SemverRange.WILDCARD);
        for (String sourceId : sourcePackIds) {
            if (!StartupPackPublisher.PACK_ID.equals(sourceId)) manifest.injectDependency(PluginIdentifier.fromString(sourceId), SemverRange.WILDCARD);
        }
        return manifest;
    }

    private record Inputs(List<PatchSource> sources, Set<String> installedIds, Map<String, String> versions,
                          ModDataRootRegistry modDataRoots, List<String> sourcePackIds) { }
}
