package com.alechilles.patchwork.embedded;

/**
 * Outcome of {@link EmbeddedPatchworkService#requestRegeneration()}.
 *
 * <p>{@code started} is {@code false} when no pass ran: no runtime is active, a pass is already
 * running, the runtime is changing owner, or the elected runtime predates this API. The counts
 * describe generated targets that changed in the pass: hot reloaded (including adapter reloads and
 * removals), waiting for a restart, or failed (including rolled-back targets). Targets whose patches
 * were rejected during generation keep their last good output and are not counted.</p>
 */
public record PatchworkRegenerationResult(boolean started, int hotReloadedTargets, int restartRequiredTargets, int failedTargets) { }
