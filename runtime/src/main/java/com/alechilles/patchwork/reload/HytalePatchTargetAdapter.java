package com.alechilles.patchwork.reload;

import java.util.Objects;

/** Explicit, narrow reload adapter; this type deliberately has no generic asset-store API. */
public final class HytalePatchTargetAdapter {
    @FunctionalInterface public interface TargetSupport { boolean supports(ReloadTarget target); }
    @FunctionalInterface public interface ReloadAction { AdapterReply reload(ReloadTarget target) throws Exception; }
    /** Target state offered to an explicitly registered reload route. */
    public record ReloadTarget(long epoch, String target, String expectedHash, boolean removal, PatchTargetClassifier.Family family) { }
    /** Adapter acceptance result; confirmation still requires an observer event. */
    public record AdapterReply(boolean accepted, boolean restartRequired, String diagnostic) {
        public AdapterReply { if (accepted && restartRequired) throw new IllegalArgumentException("An adapter cannot confirm and require restart simultaneously."); diagnostic = diagnostic == null ? "" : diagnostic; }
        public static AdapterReply confirmed() { return new AdapterReply(true, false, ""); }
        public static AdapterReply restartRequired(String diagnostic) { return new AdapterReply(false, true, diagnostic); }
        public static AdapterReply rejected(String diagnostic) { return new AdapterReply(false, false, diagnostic); }
    }
    private final String adapterId;
    private final TargetSupport support;
    private final ReloadAction action;

    public HytalePatchTargetAdapter(String adapterId, TargetSupport support, ReloadAction action) {
        this.adapterId = Objects.requireNonNull(adapterId); this.support = Objects.requireNonNull(support); this.action = Objects.requireNonNull(action);
    }
    public String adapterId() { return adapterId; }
    public boolean supports(ReloadTarget target) { return support.supports(target); }
    public AdapterReply reload(ReloadTarget target) throws Exception { return action.reload(target); }
}
