package com.alechilles.patchwork.authoring;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;

/** Creates the Hytale store that exposes Patchwork definitions to native asset tooling. */
public final class PatchDefinitionAssetStore {
    public static final String PATH = "Patchwork/Patches";
    public static final String EXTENSION = ".json";

    private PatchDefinitionAssetStore() {
    }

    public static HytaleAssetStore<String, PatchDefinitionAsset,
            DefaultAssetMap<String, PatchDefinitionAsset>> create() {
        return HytaleAssetStore.builder(PatchDefinitionAsset.class, new DefaultAssetMap<>())
                .setPath(PATH)
                .setExtension(EXTENSION)
                .setCodec(PatchDefinitionAsset.CODEC)
                .setKeyFunction(PatchDefinitionAsset::getId)
                .build();
    }
}
