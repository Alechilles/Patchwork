package com.alechilles.patchwork.reload;

/** Classifies generated targets without invoking any Hytale reload mechanism. */
public final class PatchTargetClassifier {
    /** Families with Patchwork's verified observer routes. */
    public enum Family { ASSET_STORE, PARTICLE, COMMON, NPC, CUSTOM, RESTART_REQUIRED }

    /** Returns the narrow built-in route, or restart-required when none is verified. */
    public Family classify(String target) {
        if (target.startsWith("Server/AssetStore/")) return Family.ASSET_STORE;
        if (target.startsWith("Server/Particles/")) return Family.PARTICLE;
        if (target.startsWith("Server/Common/")) return Family.COMMON;
        if (target.startsWith("Server/NPC/")) return Family.NPC;
        if (target.startsWith("Server/")) return Family.CUSTOM;
        return Family.RESTART_REQUIRED;
    }
}
