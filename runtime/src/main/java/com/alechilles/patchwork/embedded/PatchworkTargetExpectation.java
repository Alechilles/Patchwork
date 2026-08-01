package com.alechilles.patchwork.embedded;

/** Exact live-reload state expected for one generated target. */
public record PatchworkTargetExpectation(String target, String expectedHash, boolean removal) {
    public PatchworkTargetExpectation { if (target == null || target.isBlank() || expectedHash == null || expectedHash.isBlank()) throw new IllegalArgumentException("Target and expected hash are required."); }
}
