package com.alechilles.patchwork.standalone;

import com.alechilles.patchwork.authoring.PatchDefinitionAssetStore;
import com.alechilles.patchwork.embedded.StandalonePatchworkBootstrap;
import com.alechilles.patchwork.embedded.StandalonePatchworkService;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

/** Hytale plugin entrypoint that delegates standalone runtime lifecycle to Patchwork. */
public final class PatchworkPlugin extends JavaPlugin {
    private final StandalonePluginLifecycle lifecycle = new StandalonePluginLifecycle();

    public PatchworkPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override protected synchronized void setup() {
        getAssetRegistry().register(PatchDefinitionAssetStore.create());
        lifecycle.setup(() -> StandalonePatchworkBootstrap.bootstrapStandalone(this));
    }

    @Override protected synchronized void start() {
        lifecycle.start();
    }

    @Override protected synchronized void shutdown() {
        lifecycle.shutdown();
    }

    StandalonePatchworkService serviceForTest() { return lifecycle.service(); }
}
