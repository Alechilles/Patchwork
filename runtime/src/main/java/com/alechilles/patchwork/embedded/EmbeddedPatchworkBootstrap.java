package com.alechilles.patchwork.embedded;

import com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry;
import com.alechilles.patchwork.generation.GeneratedPackLayout;
import com.alechilles.patchwork.telemetry.PatchworkTelemetry;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** Hytale-facing entry point for a host that embeds the plain Patchwork runtime jar. */
public final class EmbeddedPatchworkBootstrap {
    private EmbeddedPatchworkBootstrap() { }
    /** Boots the embedded runtime from a Hytale plugin entrypoint. */
    public static EmbeddedPatchworkService bootstrap(JavaPlugin plugin) {
        if (plugin == null) throw new IllegalArgumentException("Embedding JavaPlugin is required.");
        PatchworkTelemetry telemetry = PatchworkTelemetry.prepare(plugin);
        return bootstrap(plugin, new HytaleEarlyLoadComposition(plugin, sharedLayout(plugin.getDataDirectory()), telemetry), telemetry);
    }
    /** JDK-bound composition factory used by isolated-loader verification and non-Hytale embedders. */
    static EmbeddedPatchworkService createEmbeddedService(String providerId, String runtimeVersion, Path sourceJar, Path dataRoot, Runnable electedStartupAction) {
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(testGeneratedRoot(dataRoot), Objects.requireNonNull(electedStartupAction, "electedStartupAction"));
        PatchworkRuntimeProviderHandle provider = PatchworkRuntimeProviderHandle.create(providerId, "EMBEDDED", runtimeVersion, providerId, runtimeVersion, sourceJar, dataRoot, host);
        return new Service(provider, providerId, PatchworkTelemetry.disabled());
    }
    /** Test/host composition seam; production callers use {@link #bootstrap(JavaPlugin)}. */
    static EmbeddedPatchworkService bootstrap(JavaPlugin plugin, Runnable electedStartupAction) {
        return bootstrap(plugin, new PatchworkRuntimeHost.EarlyLoadRegistrar() {
            @Override public PatchworkRuntimeHost.EarlyLoadRegistration register(long epoch, java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent> callback) { return () -> { }; }
            @Override public void execute(long epoch, com.alechilles.patchwork.engine.PatchMacroRegistry macros, com.hypixel.hytale.server.core.asset.LoadAssetEvent event, PatchworkRuntimeHost.EpochActionGate actionGate) { actionGate.execute(electedStartupAction); }
        });
    }
    /** Test-only composition seam; production callers use {@link #bootstrap(JavaPlugin)}. */
    static EmbeddedPatchworkService bootstrap(JavaPlugin plugin, PatchworkRuntimeHost.EarlyLoadRegistrar registrar) {
        return bootstrap(plugin, registrar, PatchworkTelemetry.prepare(plugin));
    }
    private static EmbeddedPatchworkService bootstrap(JavaPlugin plugin, PatchworkRuntimeHost.EarlyLoadRegistrar registrar, PatchworkTelemetry telemetry) {
        if (plugin == null) throw new IllegalArgumentException("Embedding JavaPlugin is required.");
        String runtimeVersion = requireRuntimeVersion(readMavenVersion(), EmbeddedPatchworkBootstrap.class.getPackage().getImplementationVersion());
        String pluginId = plugin.getIdentifier().toString();
        Object manifestVersion = plugin.getManifest().getVersion();
        if (manifestVersion == null || manifestVersion.toString().isBlank()) throw new IllegalStateException("Embedding plugin manifest version is required.");
        String pluginVersion = manifestVersion.toString();
        Path data = plugin.getDataDirectory(); Path source = runtimeCodeSource();
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(sharedGeneratedRoot(data), Objects.requireNonNull(registrar, "registrar"), telemetry);
        PatchworkRuntimeProviderHandle provider = PatchworkRuntimeProviderHandle.create("embedded:" + pluginId, "EMBEDDED", runtimeVersion, pluginId, pluginVersion, source, data, host);
        return new Service(provider, pluginId, telemetry);
    }
    private static Path runtimeCodeSource() {
        try { return Path.of(EmbeddedPatchworkBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize(); }
        catch (Exception failure) { throw new IllegalStateException("Patchwork runtime code-source path is unavailable.", failure); }
    }
    static String requireRuntimeVersion(String mavenVersion, String packageVersion) {
        String value = validVersion(mavenVersion) ? mavenVersion : validVersion(packageVersion) ? packageVersion : null;
        if (value == null) throw new IllegalStateException("Patchwork runtime version is unavailable; include META-INF/maven/com.alechilles/patchwork-runtime/pom.properties or an implementation version.");
        return value;
    }
    private static String readMavenVersion() {
        try (InputStream stream = EmbeddedPatchworkBootstrap.class.getClassLoader().getResourceAsStream("META-INF/maven/com.alechilles/patchwork-runtime/pom.properties")) {
            if (stream == null) return null; Properties properties = new Properties(); properties.load(stream); return properties.getProperty("version");
        } catch (IOException ignored) { return null; }
    }
    private static boolean validVersion(String value) { return value != null && value.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"); }
    private static Path sharedGeneratedRoot(Path data) { return sharedLayout(data).generatedRoot(); }
    private static Path testGeneratedRoot(Path data) {
        Path parent = Objects.requireNonNull(data, "Test data root is required.").toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IllegalStateException("Test data root has no parent for the generated Patchwork root.");
        return parent.resolve("Alechilles_Patchwork/GeneratedPatches").normalize();
    }
    private static GeneratedPackLayout sharedLayout(Path data) {
        Path pluginData = Objects.requireNonNull(data, "Embedding plugin data directory is required.").toAbsolutePath().normalize();
        Path mods = pluginData.getParent();
        if (mods == null || mods.getParent() == null || !"mods".equalsIgnoreCase(mods.getFileName().toString())) {
            throw new IllegalStateException("Cannot resolve Patchwork shared root: plugin data directory must be below <server-or-save-root>/mods/<plugin>.");
        }
        return new GeneratedPackLayout(mods.getParent());
    }
    private static final class Service implements EmbeddedPatchworkService {
        private final PatchworkRuntimeProviderHandle provider; private final String pluginId; private final PatchworkTelemetry telemetry; private final java.util.Set<Contribution> contributions = java.util.concurrent.ConcurrentHashMap.newKeySet(); private final AtomicBoolean closed = new AtomicBoolean();
        private Service(PatchworkRuntimeProviderHandle provider, String pluginId, PatchworkTelemetry telemetry) { this.provider = provider; this.pluginId = pluginId; this.telemetry = telemetry; }
        public synchronized void start() { if (closed.get()) throw new IllegalStateException("Embedded Patchwork service is closed."); telemetry.start(); provider.start(); }
        public synchronized PatchworkContributionHandle registerContribution(PatchworkHostContribution contribution) {
            if (closed.get()) throw new IllegalStateException("Embedded Patchwork service is closed."); ContributionSnapshot snapshot = ContributionSnapshot.capture(contribution); snapshot.validate();
            Contribution handle = new Contribution(snapshot); contributions.add(handle); return handle;
        }
        public Path generatedPatchRoot() { String path = PatchworkCoordinatorRegistry.generatedPatchRoot(); if (path == null) throw new IllegalStateException("No active Patchwork runtime is available."); return Path.of(path); }
        public void recordObservation(PatchworkReloadObservation observation) {
            if (closed.get()) return; Map<String, Object> map = new LinkedHashMap<>(); map.put("epoch", observation.epoch()); map.put("adapterId", observation.adapterId()); map.put("target", observation.target()); map.put("expectedHash", observation.expectedHash()); map.put("outcome", observation.outcome().name()); PatchworkCoordinatorRegistry.recordObservation(map);
        }
        public synchronized void close() { if (closed.get()) return; RuntimeException combined = null; for (Contribution contribution : List.copyOf(contributions)) try { contribution.close(); } catch (RuntimeException failure) { if (combined == null) combined = failure; else combined.addSuppressed(failure); } try { telemetry.close(); } catch (RuntimeException failure) { if (combined == null) combined = failure; else combined.addSuppressed(failure); } try { provider.close(); } catch (RuntimeException failure) { if (combined == null) combined = failure; else combined.addSuppressed(failure); } if (combined != null) throw combined; closed.set(true); }
        private final class Contribution implements PatchworkContributionHandle {
            private final String token; private final AtomicBoolean closed = new AtomicBoolean();
            private Contribution(ContributionSnapshot snapshot) {
                Map<String, Object> map = new LinkedHashMap<>(); map.put("hostPluginIdentifier", snapshot.hostPluginIdentifier()); map.put("contributionVersion", snapshot.contributionVersion());
                map.put("macroIds", snapshot.macros().stream().map(MacroEntry::id).toList()); map.put("adapterIds", snapshot.adapters().stream().map(AdapterEntry::id).toList()); map.put("bridge", new HostBridge(snapshot)); token = PatchworkCoordinatorRegistry.registerContribution(map);
            }
            public synchronized void close() { if (closed.get()) return; if (!PatchworkCoordinatorRegistry.unregisterContribution(token)) throw new IllegalStateException("Patchwork contribution could not be unregistered; retry close after the active runtime recovers."); closed.set(true); contributions.remove(this); }
        }
    }
    /** Reflection target carrying JSON strings only between candidate classloaders. */
    public static final class HostBridge {
        private final List<MacroEntry> macros;
        private final List<AdapterEntry> adapters;
        HostBridge(PatchworkHostContribution contribution) { this(ContributionSnapshot.capture(contribution)); }
        private HostBridge(ContributionSnapshot snapshot) { macros = snapshot.macros(); adapters = snapshot.adapters(); }
        public String expand(String macroId, String operationJson) {
            for (MacroEntry macro : macros) if (macro.id().equals(macroId)) return macro.provider().expand(com.google.gson.JsonParser.parseString(operationJson).getAsJsonObject()).toString();
            throw new IllegalArgumentException("Unknown host macro: " + macroId);
        }
        /** JDK-only target support method used reflectively by the elected runtime. */
        public boolean supports(String adapterId, String target, String ignoredFamily) {
            for (AdapterEntry adapter : adapters) if (adapter.id().equals(adapterId)) return adapter.adapter().supports(target);
            return false;
        }
        /** JDK-only target reload method used reflectively by the elected runtime. */
        public java.util.concurrent.CompletionStage<Map<String, ?>> reload(String adapterId, Map<String, ?> values) {
            for (AdapterEntry adapter : adapters) if (adapter.id().equals(adapterId)) {
                long epoch = ((Number) values.get("epoch")).longValue();
                String target = (String) values.get("target"); String hash = (String) values.get("expectedHash"); boolean removal = Boolean.TRUE.equals(values.get("removal"));
                return adapter.adapter().reload(new PatchworkReloadRequest(epoch, List.of(new PatchworkTargetExpectation(target, hash, removal))))
                        .thenApply(result -> Map.of("adapterId", result.adapterId(), "reloadedTargets", result.reloadedTargets(), "restartRequiredTargets", result.restartRequiredTargets(), "failures", result.failures()));
            }
            return java.util.concurrent.CompletableFuture.failedStage(new IllegalArgumentException("Unknown host adapter: " + adapterId));
        }
    }
    /** Immutable registration-time contribution view that never calls host getters again. */
    private record ContributionSnapshot(String hostPluginIdentifier, String contributionVersion, List<MacroEntry> macros, List<AdapterEntry> adapters) {
        List<PatchworkMacroProvider> macroProviders() { return macros.stream().map(MacroEntry::provider).toList(); }
        List<PatchworkTargetAdapter> targetAdapters() { return adapters.stream().map(AdapterEntry::adapter).toList(); }
        static ContributionSnapshot capture(PatchworkHostContribution contribution) {
            Objects.requireNonNull(contribution, "contribution");
            List<MacroEntry> macros = contribution.macroProviders().stream().map(provider -> new MacroEntry(provider.macroId(), provider)).toList();
            List<AdapterEntry> adapters = contribution.targetAdapters().stream().map(adapter -> new AdapterEntry(adapter.adapterId(), adapter)).toList();
            return new ContributionSnapshot(contribution.hostPluginIdentifier(), contribution.contributionVersion(), macros, adapters);
        }
        void validate() {
            if (hostPluginIdentifier == null || hostPluginIdentifier.isBlank() || contributionVersion == null || contributionVersion.isBlank()) throw new IllegalArgumentException("Contribution host identifier and version are required.");
            for (MacroEntry macro : macros) if (macro.id() == null || macro.id().isBlank()) throw new IllegalArgumentException("Each Patchwork macro must have a non-blank ID.");
            for (AdapterEntry adapter : adapters) if (adapter.id() == null || adapter.id().isBlank()) throw new IllegalArgumentException("Each Patchwork target adapter must have a non-blank ID.");
        }
    }
    private record MacroEntry(String id, PatchworkMacroProvider provider) { }
    private record AdapterEntry(String id, PatchworkTargetAdapter adapter) { }
}
