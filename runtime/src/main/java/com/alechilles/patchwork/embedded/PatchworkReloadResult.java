package com.alechilles.patchwork.embedded;

import java.util.List;

/** Structured result from one host adapter. */
public record PatchworkReloadResult(String adapterId, List<String> reloadedTargets, List<String> restartRequiredTargets, List<String> failures) {
    public PatchworkReloadResult { if (adapterId == null || adapterId.isBlank()) throw new IllegalArgumentException("Adapter ID is required."); reloadedTargets = List.copyOf(reloadedTargets); restartRequiredTargets = List.copyOf(restartRequiredTargets); failures = List.copyOf(failures); }
}
