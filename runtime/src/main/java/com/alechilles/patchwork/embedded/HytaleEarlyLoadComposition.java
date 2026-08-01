package com.alechilles.patchwork.embedded;

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
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.LoadAssetEvent;
import com.alechilles.patchwork.command.PatchworkCommandRoot;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

/** Registers and executes the elected runtime's single early asset-load generation pass. */
final class HytaleEarlyLoadComposition implements PatchworkRuntimeHost.EarlyLoadRegistrar {
    private static final System.Logger LOG = System.getLogger(HytaleEarlyLoadComposition.class.getName());
    private final JavaPlugin plugin;
    private final GeneratedPackLayout layout;
    private final HytaleRuntimeInputsSnapshotter inputs = new HytaleRuntimeInputsSnapshotter();
    private volatile PatchworkAdministrationService administration;

    HytaleEarlyLoadComposition(JavaPlugin plugin, GeneratedPackLayout layout) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    @Override public PatchworkRuntimeHost.EarlyLoadRegistration register(long epoch, Consumer<LoadAssetEvent> callback) {
        return plugin.getEventRegistry().register(PatchworkEarlyLoadHook.PRIORITY, LoadAssetEvent.class, callback)::unregister;
    }

    @Override public PatchworkRuntimeHost.CommandRegistrationHandle registerCommands(com.alechilles.patchwork.command.PatchworkCommandActions actions) {
        // Hytale exposes no lifecycle-thread executor for command mutation. This mirrors its
        // MacroCommandPlugin rebuild path (unregister then register); Patchwork additionally fences
        // and drains its own command actions before ownership releases this registration.
        var registration = plugin.getCommandRegistry().registerCommand(new PatchworkCommandRoot(PatchworkCommandRoot.ADMIN_PERMISSION, PatchworkCommandRoot.DEFAULT_GROUP, actions));
        return registration == null ? null : registration::unregister;
    }

    @Override public PatchworkAdministrationService createAdministration(PatchworkRuntimeHost host) {
        PatchworkAdministrationService created = new PatchworkAdministrationService(
                () -> planFor(host.macros()).createPlan(),
                () -> host.reloadCoordinator(java.time.Duration.ofSeconds(3))::reload,
                () -> selfTestExecutor(new com.alechilles.patchwork.selftest.PatchworkSelfTestRunner(layout)),
                GeneratedInventorySnapshotter.from(layout.generatedRoot()));
        administration = created;
        return created;
    }

    private static SelfTestExecutor selfTestExecutor(com.alechilles.patchwork.selftest.PatchworkSelfTestRunner runner) {
        return new SelfTestExecutor() {
            @Override public com.alechilles.patchwork.selftest.PatchworkSelfTestResult run(com.alechilles.patchwork.selftest.PatchworkSelfTestPack pack) { return runner.run(pack); }
            @Override public void cancel() { runner.cancel(); }
        };
    }

    @Override public void execute(long epoch, PatchMacroRegistry macros, LoadAssetEvent event, PatchworkRuntimeHost.EpochActionGate actionGate) {
        try {
            com.alechilles.patchwork.generation.PatchGenerationService.GenerationPlan plan = planFor(macros).createPlan();
            StartupPackPublisher publisher = new StartupPackPublisher(layout, new RuntimePackRegistrar(plan.sourcePackIds()));
            AtomicReference<StartupPackPublisher.Publication> publicationResult = new AtomicReference<>();
            if (!actionGate.execute(() -> publicationResult.set(publisher.publish(plan)))) return;
            StartupPackPublisher.Publication publication = publicationResult.get();
            if (!publication.published()) fail(event, "Patchwork startup generation failed: " + publication.diagnostic());
            else {
                PatchworkAdministrationService currentAdministration = administration;
                if (currentAdministration != null) currentAdministration.seedStartup(epoch, plan);
                if (!plan.status().scanFailures().isEmpty() || !plan.status().rejectedTargets().isEmpty()) {
                    fail(event, "Patchwork generated valid targets with recoverable diagnostics: " + plan.status().scanFailures().size() + " scan failure(s), " + plan.status().rejectedTargets().size() + " rejected target(s).");
                }
            }
        } catch (Exception failure) {
            String message = "Patchwork startup generation failed: " + detail(failure);
            LOG.log(System.Logger.Level.ERROR, message, failure);
            fail(event, message);
        }
    }

    private GenerationPlanFactory planFor(PatchMacroRegistry macros) {
        return new GenerationPlanFactory(macros, inputs::snapshot, com.hypixel.hytale.common.util.java.ManifestUtil::getVersion,
                com.alechilles.patchwork.conditions.ConditionDocumentCache::new,
                (roots, cache) -> new com.alechilles.patchwork.conditions.ConditionSourceResolver(new com.alechilles.patchwork.discovery.PatchTargetResolver(), roots, cache),
                metadata -> {
                    PatchworkAdministrationService current = administration;
                    if (current != null) current.configureRoots(metadata.neutralRoot(), metadata.legacyRoots());
                });
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

}
