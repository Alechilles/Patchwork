package com.alechilles.patchwork.reload;

import com.alechilles.patchwork.discovery.PatchScanner;
import java.util.Objects;

/**
 * A normalized source-side event used by the automatic reload controller.
 * Hytale events are deliberately converted to this small value object before
 * they reach the controller, keeping the scheduling and dependency matching
 * code independent of the engine event classes.
 */
public record PatchworkSourceEvent(Kind kind, String sourcePackId, String assetPath, boolean mutable) {
    public enum Kind {
        DEFINITION_CREATED,
        DEFINITION_MODIFIED,
        DEFINITION_REMOVED,
        ASSET_CREATED,
        ASSET_MODIFIED,
        ASSET_REMOVED,
        PACK_REGISTERED,
        PACK_REMOVED,
        MONITOR_OVERFLOW,
        MONITOR_STOPPED
    }

    public PatchworkSourceEvent {
        kind = Objects.requireNonNull(kind, "kind");
        sourcePackId = Objects.requireNonNull(sourcePackId, "sourcePackId");
        assetPath = assetPath == null ? "" : normalize(assetPath);
    }

    public static PatchworkSourceEvent created(String packId, String path, boolean mutable) {
        return new PatchworkSourceEvent(Kind.ASSET_CREATED, packId, path, mutable);
    }

    public static PatchworkSourceEvent created(String packId, String path) {
        return created(packId, path, true);
    }

    public static PatchworkSourceEvent modified(String packId, String path, boolean mutable) {
        return new PatchworkSourceEvent(Kind.ASSET_MODIFIED, packId, path, mutable);
    }

    public static PatchworkSourceEvent modified(String packId, String path) {
        return modified(packId, path, true);
    }

    public static PatchworkSourceEvent removed(String packId, String path, boolean mutable) {
        return new PatchworkSourceEvent(Kind.ASSET_REMOVED, packId, path, mutable);
    }

    public static PatchworkSourceEvent removed(String packId, String path) {
        return removed(packId, path, true);
    }

    public static PatchworkSourceEvent definitionCreated(String packId, String path) {
        return new PatchworkSourceEvent(Kind.DEFINITION_CREATED, packId, path, true);
    }

    public static PatchworkSourceEvent definitionModified(String packId, String path) {
        return new PatchworkSourceEvent(Kind.DEFINITION_MODIFIED, packId, path, true);
    }

    public static PatchworkSourceEvent definitionRemoved(String packId, String path) {
        return new PatchworkSourceEvent(Kind.DEFINITION_REMOVED, packId, path, true);
    }

    public static PatchworkSourceEvent packRegistered(String packId, boolean mutable) {
        return new PatchworkSourceEvent(Kind.PACK_REGISTERED, packId, "", mutable);
    }

    public static PatchworkSourceEvent packRemoved(String packId, boolean mutable) {
        return new PatchworkSourceEvent(Kind.PACK_REMOVED, packId, "", mutable);
    }

    public static PatchworkSourceEvent monitorOverflow(String packId) {
        return new PatchworkSourceEvent(Kind.MONITOR_OVERFLOW, packId, "", false);
    }

    public static PatchworkSourceEvent monitorStopped(String packId) {
        return new PatchworkSourceEvent(Kind.MONITOR_STOPPED, packId, "", false);
    }

    public boolean generatedPack() {
        return PatchScanner.GENERATED_PACK_ID.equals(sourcePackId);
    }

    private static String normalize(String value) {
        String candidate = value.replace('\\', '/');
        if (candidate.isBlank()) return "";
        try {
            return PatchScanner.normalizeAssetPath(candidate);
        } catch (IllegalArgumentException ignored) {
            // Pack and monitor events may carry an absolute path. The bridge
            // normalizes those against the pack root; keep unusual values here
            // so they can still be treated as a broad invalidation event.
            return candidate;
        }
    }
}
