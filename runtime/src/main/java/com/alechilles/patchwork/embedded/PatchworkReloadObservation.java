package com.alechilles.patchwork.embedded;

/** Immutable host observation correlated by epoch, target, and expected hash. */
public record PatchworkReloadObservation(long epoch, String adapterId, String target, String expectedHash, PatchworkObservationOutcome outcome) {
    public PatchworkReloadObservation { if (adapterId == null || adapterId.isBlank() || target == null || target.isBlank() || expectedHash == null || expectedHash.isBlank() || outcome == null) throw new IllegalArgumentException("Observation fields are required."); }
}
