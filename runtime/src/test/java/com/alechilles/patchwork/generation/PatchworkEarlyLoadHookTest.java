package com.alechilles.patchwork.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tests owner/epoch guards for the startup-only event callback. */
final class PatchworkEarlyLoadHookTest {
    @Test
    void passiveOrRevokedEpochDoesNothingBeforeAnySideEffect() {
        AtomicInteger sideEffects = new AtomicInteger();
        PatchworkEarlyLoadHook hook = new PatchworkEarlyLoadHook(() -> false, () -> lease -> sideEffects.incrementAndGet());

        hook.onLoadAssetEvent(null);

        assertEquals(0, sideEffects.get());
        assertEquals(-39, PatchworkEarlyLoadHook.PRIORITY);
    }

    @Test
    void activeOwnerRunsFencedActionOnce() {
        AtomicInteger effects = new AtomicInteger();
        new PatchworkEarlyLoadHook(() -> true, () -> lease -> { if (lease.isActive()) effects.incrementAndGet(); }).onLoadAssetEvent(null);
        assertEquals(1, effects.get());
    }

    @Test
    void revocationAfterSupplierPreventsNonCooperativeActionInvocation() {
        AtomicInteger effects = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        PatchworkEarlyLoadHook.Lease lease = active::get;
        new PatchworkEarlyLoadHook(lease, () -> {
            active.set(false);
            return fenced -> effects.incrementAndGet();
        }).onLoadAssetEvent(null);
        assertEquals(0, effects.get());
    }
}
