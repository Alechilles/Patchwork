package com.alechilles.patchwork.reload;

import java.util.Objects;

/** Passive observer entry point; observations never initiate generation or reloads. */
public final class GeneratedPackObserver {
    private final PatchReloadTracker tracker;

    public GeneratedPackObserver(PatchReloadTracker tracker) { this.tracker = Objects.requireNonNull(tracker); }

    /** Records a watcher notification only when it matches a pending transaction. */
    public boolean observe(PatchReloadTracker.Observation observation) { return tracker.record(Objects.requireNonNull(observation)); }
}
