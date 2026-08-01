package com.alechilles.patchwork.embedded;

import java.nio.file.Path;

/** Stable handle retained by an embedding plugin across coordinator ownership changes. */
public interface EmbeddedPatchworkService extends AutoCloseable {
    void start();
    PatchworkContributionHandle registerContribution(PatchworkHostContribution contribution);
    Path generatedPatchRoot();
    void recordObservation(PatchworkReloadObservation observation);
    @Override void close();
}
