package com.alechilles.patchwork.embedded;

import java.nio.file.Path;

/** Stable handle retained by an embedding plugin across coordinator ownership changes. */
public interface EmbeddedPatchworkService extends AutoCloseable {
    /** Elects the provider and registers its early asset-load callback; call this during plugin setup. */
    void start();
    PatchworkContributionHandle registerContribution(PatchworkHostContribution contribution);
    Path generatedPatchRoot();
    void recordObservation(PatchworkReloadObservation observation);
    @Override void close();
}
