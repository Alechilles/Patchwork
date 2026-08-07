package com.alechilles.patchwork.embedded;

import com.alechilles.patchwork.generation.GeneratedPackLayout;
import com.alechilles.patchwork.telemetry.PatchworkTelemetry;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/** Composes the standalone Hytale plugin into the shared Patchwork runtime. */
public final class StandalonePatchworkBootstrap {
    private StandalonePatchworkBootstrap() { }

    /** Creates the standalone provider without registering it; {@link StandalonePatchworkService#start()} elects it. */
    public static StandalonePatchworkService bootstrapStandalone(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        String runtimeVersion = EmbeddedPatchworkBootstrap.requireRuntimeVersion(readMavenVersion(), StandalonePatchworkBootstrap.class.getPackage().getImplementationVersion());
        String pluginId = plugin.getIdentifier().toString();
        Object manifestVersion = plugin.getManifest().getVersion();
        if (manifestVersion == null || manifestVersion.toString().isBlank()) throw new IllegalStateException("Standalone plugin manifest version is required.");
        Path dataRoot = plugin.getDataDirectory();
        Path sourceJar = plugin.getFile().toAbsolutePath().normalize();
        GeneratedPackLayout layout = sharedLayout(dataRoot);
        PatchworkTelemetry telemetry = PatchworkTelemetry.prepare(plugin);
        PatchworkRuntimeProviderHandle provider = PatchworkRuntimeProviderHandle.create(
                "standalone:" + pluginId, "STANDALONE", runtimeVersion, pluginId, manifestVersion.toString(), sourceJar, dataRoot,
                new PatchworkRuntimeHost(layout.generatedRoot(), new HytaleEarlyLoadComposition(plugin, layout, telemetry), telemetry));
        return new Service(provider, telemetry);
    }

    static StandalonePatchworkService createStandaloneService(String providerId, String runtimeVersion, Path sourceJar,
                                                               Path dataRoot, Runnable electedStartupAction) {
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(testGeneratedRoot(dataRoot), Objects.requireNonNull(electedStartupAction, "electedStartupAction"));
        return new Service(PatchworkRuntimeProviderHandle.create(providerId, "STANDALONE", runtimeVersion, providerId,
                runtimeVersion, sourceJar, dataRoot, host), PatchworkTelemetry.disabled());
    }

    private static String readMavenVersion() {
        try (InputStream stream = StandalonePatchworkBootstrap.class.getClassLoader().getResourceAsStream("META-INF/maven/com.alechilles/patchwork-runtime/pom.properties")) {
            if (stream == null) return null;
            Properties properties = new Properties();
            properties.load(stream);
            return properties.getProperty("version");
        } catch (IOException ignored) {
            return null;
        }
    }

    private static GeneratedPackLayout sharedLayout(Path dataRoot) {
        Path pluginData = Objects.requireNonNull(dataRoot, "Standalone plugin data directory is required.").toAbsolutePath().normalize();
        Path mods = pluginData.getParent();
        if (mods == null || mods.getParent() == null || !"mods".equalsIgnoreCase(mods.getFileName().toString())) {
            throw new IllegalStateException("Cannot resolve Patchwork shared root: plugin data directory must be below <server-or-save-root>/mods/<plugin>.");
        }
        return new GeneratedPackLayout(mods.getParent());
    }

    private static Path testGeneratedRoot(Path dataRoot) {
        Path parent = Objects.requireNonNull(dataRoot, "Test data root is required.").toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IllegalStateException("Test data root has no parent for the generated Patchwork root.");
        return parent.resolve("Alechilles_Patchwork/GeneratedPatches").normalize();
    }

    private record Service(PatchworkRuntimeProviderHandle provider, PatchworkTelemetry telemetry) implements StandalonePatchworkService {
        @Override public void start() { telemetry.start(); provider.start(); }
        @Override public void close() { telemetry.close(); provider.close(); }
    }
}
