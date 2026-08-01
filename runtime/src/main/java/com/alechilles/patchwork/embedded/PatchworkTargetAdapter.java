package com.alechilles.patchwork.embedded;

import java.util.concurrent.CompletionStage;

/** Narrow host reload route; it deliberately does not expose generic asset-store reload. */
public interface PatchworkTargetAdapter {
    String adapterId();
    boolean supports(String target);
    /**
     * Starts the host-specific reload.  Confirmation is delivered separately through
     * {@link EmbeddedPatchworkService#recordObservation(PatchworkReloadObservation)}.
     */
    CompletionStage<PatchworkReloadResult> reload(PatchworkReloadRequest request);
}
