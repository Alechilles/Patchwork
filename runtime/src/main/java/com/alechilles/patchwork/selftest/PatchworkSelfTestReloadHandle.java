package com.alechilles.patchwork.selftest;

import java.nio.file.Path;
import java.util.List;

/** Optional loader-local observation seam; Patchwork never claims an unverified Hytale reload succeeded. */
public interface PatchworkSelfTestReloadHandle {
    /** Reloads only the isolated output supplied by the runner. */
    ReloadOutcome reloadIsolated(IsolatedGeneration generation);
    default void cancel() { }

    /** Immutable isolated loader input with no production output path. */
    record IsolatedGeneration(Path generatedRoot, List<String> targets) { public IsolatedGeneration { targets = List.copyOf(targets); } }
    /** Truthful loader-local state; no state is inferred by Patchwork. */
    enum ReloadOutcome { HOT_RELOADED, ADAPTER_RELOADED, RESTART_REQUIRED, REMOVED, STALE, ROLLBACK_FAILED, FAILED }
}
