package com.alechilles.patchwork.embedded;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/** Stable handle retained by an embedding plugin across coordinator ownership changes. */
public interface EmbeddedPatchworkService extends AutoCloseable {
    /** Elects the provider and registers its early asset-load callback; call this during plugin setup. */
    void start();
    PatchworkContributionHandle registerContribution(PatchworkHostContribution contribution);
    Path generatedPatchRoot();
    void recordObservation(PatchworkReloadObservation observation);
    /** Asks the elected runtime to run a normal regeneration pass (same as /patchwork reload). Never throws for "not started"; reports it in the result. */
    CompletionStage<PatchworkRegenerationResult> requestRegeneration();
    @Override void close();
}
