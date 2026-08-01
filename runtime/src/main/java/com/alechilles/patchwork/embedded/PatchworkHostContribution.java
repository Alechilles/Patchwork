package com.alechilles.patchwork.embedded;

import java.util.List;

/** Versioned macro and reload capabilities supplied by an embedding host. */
public interface PatchworkHostContribution {
    String hostPluginIdentifier();
    String contributionVersion();
    List<PatchworkMacroProvider> macroProviders();
    List<PatchworkTargetAdapter> targetAdapters();

    static void validate(String host, String version, List<?> macros, List<?> adapters) {
        if (host == null || host.isBlank() || version == null || version.isBlank() || macros == null || adapters == null) {
            throw new IllegalArgumentException("Contribution host identifier, version, macros, and adapters are required.");
        }
        for (Object macro : macros) {
            if (!(macro instanceof PatchworkMacroProvider provider) || provider.macroId() == null || provider.macroId().isBlank()) {
                throw new IllegalArgumentException("Each Patchwork macro must have a non-blank ID.");
            }
        }
        for (Object adapter : adapters) {
            if (!(adapter instanceof PatchworkTargetAdapter targetAdapter) || targetAdapter.adapterId() == null || targetAdapter.adapterId().isBlank()) {
                throw new IllegalArgumentException("Each Patchwork target adapter must have a non-blank ID.");
            }
        }
    }
}
