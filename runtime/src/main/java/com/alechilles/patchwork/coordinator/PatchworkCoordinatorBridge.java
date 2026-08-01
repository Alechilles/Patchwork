package com.alechilles.patchwork.coordinator;

import java.util.List;
import java.util.Map;

/**
 * Reflection-safe lifecycle boundary for a candidate provider. Implementations must use only JDK-owned
 * types in public signatures so separately loaded runtime jars can cooperate.
 */
public interface PatchworkCoordinatorBridge {
    default void fence(long epoch) { }
    default void stopAcceptingAndDrain(long epoch) { }
    default void deactivate(long epoch) { }
    default void activate(long epoch) { }
    default void start(long epoch) { }
    default boolean publish(long epoch) { return true; }
    /** Receives an immutable JDK-only contribution snapshot after election. */
    default void replayContributions(long epoch, List<Map<String, ?>> contributions) { }
    /** Returns the shared generated root as a string, never a host object. */
    default String generatedPatchRoot() { return null; }
    /** Records an exact reload observation without starting generation. */
    default boolean recordObservation(Map<String, ?> observation) { return false; }
    /** Expands a macro operation encoded as JSON across the JDK-only loader boundary. */
    default String expandOperationJson(String operationJson) { throw new IllegalStateException("Macro expansion is unavailable."); }
}
