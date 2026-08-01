package com.alechilles.patchwork.embedded;

import com.alechilles.patchwork.conditions.ModDataRootRegistry;
import com.alechilles.patchwork.discovery.PatchSource;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.server.core.asset.AssetModule;
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
import java.util.Set;

/**
 * Captures one immutable Hytale asset/plugin input view for a single generation pass.
 *
 * <p>Hytale replaces {@code AssetModule}'s pack-list field while holding
 * {@link AssetRegistry#ASSET_LOCK}. Taking the corresponding read lock for the copy gives reloads
 * a formally visible list reference without retaining that engine lock during filesystem work.
 * {@link PluginManager#getPlugins()} performs its own locked copy.</p>
 */
final class HytaleRuntimeInputsSnapshotter {
    /** Snapshotting is deliberately repeated by startup and every admitted reload. */
    Inputs snapshot() {
        List<PatchSource> sources = new ArrayList<>();
        List<String> sourceIds = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Map<String, String> versions = new LinkedHashMap<>();
        int order = 0;
        for (AssetPack pack : assetPacks()) {
            String id = pack.getName();
            sourceIds.add(id); ids.add(id);
            if (pack.getManifest() != null && pack.getManifest().getVersion() != null) versions.put(id, pack.getManifest().getVersion().toString());
            boolean directory = pack.getFileSystem() == null || pack.getFileSystem().equals(FileSystems.getDefault());
            Path root = directory ? pack.getRoot() : pack.getPackLocation();
            if (!directory && (root == null || !Files.isRegularFile(root))) throw new IllegalStateException("Asset pack " + id + " has no readable archive pack location.");
            sources.add(directory ? PatchSource.directory(id, order++, root) : PatchSource.archive(id, order++, root));
        }
        PluginManager manager = PluginManager.get();
        for (PluginBase loaded : manager.getPlugins()) {
            String id = loaded.getIdentifier().toString();
            ids.add(id); versions.put(id, loaded.getManifest().getVersion().toString());
        }
        return new Inputs(List.copyOf(sources), Set.copyOf(ids), Map.copyOf(versions), ModDataRootRegistry.fromPluginManager(manager), List.copyOf(sourceIds));
    }

    private static List<AssetPack> assetPacks() {
        var lock = AssetRegistry.ASSET_LOCK.readLock();
        lock.lock();
        try {
            return List.copyOf(AssetModule.get().getAssetPacks());
        } finally {
            lock.unlock();
        }
    }

    /** Local immutable snapshot; it never crosses the coordinator classloader boundary. */
    record Inputs(List<PatchSource> sources, Set<String> installedIds, Map<String, String> versions,
                  ModDataRootRegistry modDataRoots, List<String> sourcePackIds) { }
}
