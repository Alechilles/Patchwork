package com.alechilles.patchwork.coordinator;

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
}
