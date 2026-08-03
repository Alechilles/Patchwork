package com.alechilles.patchwork.reload;

import com.alechilles.patchwork.discovery.PatchRoot;
import com.alechilles.patchwork.discovery.PatchScanner;
import com.alechilles.patchwork.generation.StartupPackPublisher;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.event.AssetStoreMonitorEvent;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RegisterAssetStoreEvent;
import com.hypixel.hytale.assetstore.event.RemoveAssetStoreEvent;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.AssetPackRegisterEvent;
import com.hypixel.hytale.server.core.asset.AssetPackUnregisterEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** Bridges ordinary Hytale asset events to automatic source and reload evidence paths. */
public final class HytaleAssetEventBridge implements AutoCloseable {
    private final JavaPlugin plugin;
    private final long epoch;
    private final AutomaticReloadController controller;
    private final HytaleReloadEvidenceCorrelator correlator;
    private final PatchReloadTracker tracker;
    private final Path generatedRoot;
    private final List<Runnable> unregister = new ArrayList<>();
    private final Set<Class<?>> registeredAssetClasses = new HashSet<>();
    private final HytalePatchTargetAdapter adapter;
    private boolean registered;

    public HytaleAssetEventBridge(JavaPlugin plugin, long epoch, AutomaticReloadController controller,
                                  PatchReloadTracker tracker, Path generatedRoot) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.epoch = epoch;
        this.controller = Objects.requireNonNull(controller, "controller");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.generatedRoot = Objects.requireNonNull(generatedRoot, "generatedRoot").toAbsolutePath().normalize();
        this.correlator = new HytaleReloadEvidenceCorrelator(tracker, this.generatedRoot);
        this.adapter = new HytalePatchTargetAdapter("hytale-asset-monitor", this::supports, this::reload);
    }

    public HytalePatchTargetAdapter adapter() { return adapter; }
    public HytaleReloadEvidenceCorrelator correlator() { return correlator; }

    /** Registers the store, pack, and file-monitor events for this elected epoch. */
    public synchronized AutoCloseable register() {
        if (registered) return this;
        registered = true;
        try {
            var storeRegistration = plugin.getEventRegistry().register(RegisterAssetStoreEvent.class, this::onStoreRegistered);
            unregister.add(storeRegistration::unregister);
            var removedStore = plugin.getEventRegistry().register(RemoveAssetStoreEvent.class, this::onStoreRemoved);
            unregister.add(removedStore::unregister);
            var pack = plugin.getEventRegistry().register(AssetPackRegisterEvent.class, this::onPackRegistered);
            unregister.add(pack::unregister);
            var removedPack = plugin.getEventRegistry().register(AssetPackUnregisterEvent.class, this::onPackRemoved);
            unregister.add(removedPack::unregister);
            var monitor = plugin.getEventRegistry().register(AssetStoreMonitorEvent.class, this::onMonitor);
            unregister.add(monitor::unregister);
            for (AssetStore<?, ?, ?> store : AssetRegistry.getStoreMap().values()) registerAssetClassEvents(store.getAssetClass());
            return this;
        } catch (RuntimeException failure) {
            registered = false;
            for (Runnable action : List.copyOf(unregister)) {
                try { action.run(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            unregister.clear();
            registeredAssetClasses.clear();
            throw failure;
        }
    }

    @Override public synchronized void close() {
        if (!registered) return;
        registered = false;
        correlator.cancelAll();
        RuntimeException combined = null;
        for (Runnable action : List.copyOf(unregister)) {
            try { action.run(); }
            catch (RuntimeException failure) { if (combined == null) combined = failure; else combined.addSuppressed(failure); }
        }
        unregister.clear();
        registeredAssetClasses.clear();
        if (combined != null) throw combined;
    }

    private boolean supports(HytalePatchTargetAdapter.ReloadTarget target) {
        if (!monitorEnabled()) return false;
        return switch (target.family()) {
            case ASSET_STORE, PARTICLE, NPC -> true;
            case COMMON, CUSTOM, RESTART_REQUIRED -> false;
        };
    }

    private HytalePatchTargetAdapter.AdapterReply reload(HytalePatchTargetAdapter.ReloadTarget target) {
        if (!monitorEnabled()) return HytalePatchTargetAdapter.AdapterReply.restartRequired("Hytale file monitoring is disabled.");
        correlator.expect(new HytaleReloadEvidenceCorrelator.Expectation(
                target.token(), target.epoch(), target.target(), target.expectedHash(), target.removal(), StartupPackPublisher.PACK_ID));
        return HytalePatchTargetAdapter.AdapterReply.confirmed();
    }

    private void onStoreRegistered(RegisterAssetStoreEvent event) {
        AssetStore<?, ?, ?> store = event.getAssetStore();
        registerAssetClassEvents(store.getAssetClass());
        controller.onSourceEvent(epoch, PatchworkSourceEvent.packRegistered("", true));
    }

    private void onStoreRemoved(RemoveAssetStoreEvent event) {
        controller.onSourceEvent(epoch, PatchworkSourceEvent.packRemoved("", true));
    }

    private void onPackRegistered(AssetPackRegisterEvent event) {
        AssetPack pack = event.getAssetPack();
        controller.onSourceEvent(epoch, PatchworkSourceEvent.packRegistered(pack.getName(), !pack.isImmutable()));
    }

    private void onPackRemoved(AssetPackUnregisterEvent event) {
        AssetPack pack = event.getAssetPack();
        controller.onSourceEvent(epoch, PatchworkSourceEvent.packRemoved(pack.getName(), !pack.isImmutable()));
    }

    private void onMonitor(AssetStoreMonitorEvent event) {
        String packId = event.getAssetPack();
        boolean generated = PatchScanner.GENERATED_PACK_ID.equals(packId);
        boolean mutable = mutablePack(packId);
        for (Path path : event.getCreatedOrModifiedFilesToLoad()) {
            String normalized = relativeAssetPath(packId, path);
            if (!generated) controller.onSourceEvent(epoch, sourceEvent(normalized, packId, mutable, false));
        }
        for (Path path : event.getRemovedFilesToUnload()) {
            String normalized = relativeAssetPath(packId, path);
            if (!generated) controller.onSourceEvent(epoch, sourceEvent(normalized, packId, mutable, true));
        }
        // Directory notifications do not identify a concrete asset. They still
        // invalidate glob prefixes and definitions under that directory.
        for (Path path : event.getCreatedOrModifiedDirectories()) {
            if (!generated) controller.onSourceEvent(epoch, PatchworkSourceEvent.modified(packId, relativeAssetPath(packId, path), mutable));
        }
        for (Path path : event.getRemovedFilesAndDirectories()) {
            if (!generated) controller.onSourceEvent(epoch, PatchworkSourceEvent.removed(packId, relativeAssetPath(packId, path), mutable));
        }
    }

    /** Called after Hytale has loaded the changed assets into the store. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void onLoaded(LoadedAssetsEvent<?, ?, ?> event) {
        AssetMap map = (AssetMap) event.getAssetMap();
        for (Object key : event.getLoadedAssets().keySet()) {
            String provider = text(map.getAssetPack(key));
            Path path = map.getPath(key);
            confirmPostLoad(provider, path, false);
        }
    }

    /** Called after Hytale has removed the changed assets from the store. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void onRemoved(RemovedAssetsEvent<?, ?, ?> event) {
        AssetMap map = (AssetMap) event.getAssetMap();
        for (Object key : event.getRemovedAssets()) {
            String provider = text(map.getAssetPack(key));
            Path path = map.getPath(key);
            // A removed generated asset may reveal a lower-priority provider;
            // the correlator rejects a still-generated provider and requires
            // this post-removal path evidence before completing the tracker.
            confirmPostLoad(provider, path, true);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private synchronized void registerAssetClassEvents(Class<?> assetClass) {
        if (!registered || !registeredAssetClasses.add(assetClass)) return;
        var loaded = plugin.getEventRegistry().register(LoadedAssetsEvent.class, (Class) assetClass,
                (LoadedAssetsEvent event) -> onLoaded(event));
        unregister.add(loaded::unregister);
        var removed = plugin.getEventRegistry().register(RemovedAssetsEvent.class, (Class) assetClass,
                (RemovedAssetsEvent event) -> onRemoved(event));
        unregister.add(removed::unregister);
    }

    private void confirmPostLoad(String provider, Path path, boolean removal) {
        if (path == null) return;
        String normalized = relativeAssetPath(provider, path);
        correlator.confirmAny(provider, normalized, removal);
    }

    private static String text(Object value) { return value == null ? null : value.toString(); }

    private PatchworkSourceEvent sourceEvent(String path, String packId, boolean mutable, boolean removal) {
        boolean definition = path.startsWith(PatchRoot.NEUTRAL.path() + "/") || path.startsWith(PatchRoot.LEGACY.path() + "/");
        if (definition && path.endsWith(".json")) {
            return removal ? PatchworkSourceEvent.definitionRemoved(packId, path) : PatchworkSourceEvent.definitionModified(packId, path);
        }
        return removal ? PatchworkSourceEvent.removed(packId, path, mutable) : PatchworkSourceEvent.modified(packId, path, mutable);
    }

    private boolean monitorEnabled() {
        try { return AssetModule.get() != null && AssetModule.get().getAssetMonitor() != null; }
        catch (RuntimeException unavailable) { return false; }
    }

    private boolean mutablePack(String packId) {
        try {
            AssetPack pack = AssetModule.get().getAssetPack(packId);
            return pack == null || !pack.isImmutable();
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    private String relativeAssetPath(String packId, Path raw) {
        if (raw == null) return "";
        String value = raw.toAbsolutePath().normalize().toString().replace('\\', '/');
        try {
            AssetPack pack = AssetModule.get().getAssetPack(packId);
            if (pack != null) {
                Path root = pack.getRoot().toAbsolutePath().normalize();
                Path candidate = raw.toAbsolutePath().normalize();
                if (candidate.startsWith(root)) value = root.relativize(candidate).toString().replace('\\', '/');
            }
        } catch (RuntimeException ignored) { }
        while (value.startsWith("/")) value = value.substring(1);
        return value;
    }
}
