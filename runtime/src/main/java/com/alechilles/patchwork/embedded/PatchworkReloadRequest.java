package com.alechilles.patchwork.embedded;

import java.util.List;

/** Immutable adapter request for one coordinator epoch. */
public record PatchworkReloadRequest(long epoch, List<PatchworkTargetExpectation> targets) {
    public PatchworkReloadRequest { targets = List.copyOf(targets); }
}
