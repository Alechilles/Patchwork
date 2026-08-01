package com.alechilles.patchwork.generation;

import com.hypixel.hytale.server.core.asset.LoadAssetEvent;
import java.util.Objects;
import java.util.function.Supplier;

/** Elected-runtime startup adapter; it has no reload behavior and performs no passive-side effects. */
public final class PatchworkEarlyLoadHook {
    /** Current Tamework patcher priority, retained so Patchwork runs first at the same early point. */
    public static final short PRIORITY = -39;
    private final Lease lease;
    private final Supplier<FencedStartupAction> startupAction;
    /** Injects narrow lease and startup collaborators; Task 8 supplies the coordinator implementation. */
    public PatchworkEarlyLoadHook(Lease lease, Supplier<FencedStartupAction> startupAction) { this.lease = Objects.requireNonNull(lease); this.startupAction = Objects.requireNonNull(startupAction); }
    /** Handles only the early asset-load event; the action must recheck the supplied lease at every side-effect boundary. */
    public void onLoadAssetEvent(LoadAssetEvent event) {
        if (!lease.isActive()) return;
        FencedStartupAction action = startupAction.get();
        if (!lease.isActive()) return;
        action.runIfActive(lease);
    }
    /** Narrow Task 8 seam for ownership plus epoch fencing. */
    public interface Lease { boolean isActive(); }
    /** Fenced action contract that lets generation/publication stop after a lease revocation. */
    @FunctionalInterface public interface FencedStartupAction { void runIfActive(Lease lease); }
}
