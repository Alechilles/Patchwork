package com.alechilles.patchwork.command;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Loader-local administration boundary used by the Hytale command objects.
 *
 * <p>The command classes intentionally know neither the elected host nor any generation
 * implementation; this keeps command ownership on the elected runtime side of a classloader
 * boundary.</p>
 *
 * <p>These actions run synchronously within Hytale's {@code AbstractAsyncCommand}
 * execution path and only read Hytale registries. Registry mutation stays in the
 * runtime lifecycle path; command actions must not mutate world or ECS state.</p>
 */
public interface PatchworkCommandActions {
    /** Returns already-sanitized status lines. */
    CompletionStage<List<String>> status();

    /** Starts an admitted reload, or returns a non-started result line. */
    CompletionStage<List<String>> reload();

    /** Starts an admitted isolated self-test, or returns a non-started result line. */
    CompletionStage<List<String>> selfTest();
}
