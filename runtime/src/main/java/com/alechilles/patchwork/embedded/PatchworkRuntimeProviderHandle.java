package com.alechilles.patchwork.embedded;

import com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one exact elected-runtime candidate registration. */
public final class PatchworkRuntimeProviderHandle implements AutoCloseable {
    private final Map<String, Object> descriptor;
    private volatile String token;
    private final AtomicBoolean closed = new AtomicBoolean();
    private PatchworkRuntimeProviderHandle(Map<String, Object> descriptor) { this.descriptor = Map.copyOf(descriptor); }
    static PatchworkRuntimeProviderHandle create(String providerId, String origin, String runtimeVersion,
                                                    String pluginId, String pluginVersion, Path sourceJar, Path dataRoot, PatchworkRuntimeHost host) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerId", providerId); descriptor.put("origin", origin); descriptor.put("runtimeVersion", runtimeVersion);
        descriptor.put("coordinatorAbi", PatchworkCoordinatorRegistry.COORDINATOR_ABI); descriptor.put("providerPluginId", pluginId);
        descriptor.put("providerPluginVersion", pluginVersion); descriptor.put("sourceJarPath", sourceJar); descriptor.put("providerDataRoot", dataRoot); descriptor.put("bridge", host);
        return new PatchworkRuntimeProviderHandle(descriptor);
    }
    String token() { return token; }
    /** Idempotently registers this exact candidate; bootstrap alone has no election side effect. */
    public synchronized void start() {
        if (closed.get()) throw new IllegalStateException("Patchwork runtime provider is closed.");
        if (token == null) token = PatchworkCoordinatorRegistry.register(descriptor);
        if ("RECOVERY_REQUIRED".equals(PatchworkCoordinatorRegistry.registrationState(token))) {
            throw new IllegalStateException("Patchwork runtime registration requires lifecycle cleanup; close this provider to retry it.");
        }
        PatchworkCoordinatorRegistry.publish(token);
    }
    public boolean publish() { String current = token; return !closed.get() && current != null && PatchworkCoordinatorRegistry.publish(current); }
    @Override public synchronized void close() {
        if (closed.get()) return;
        String current = token;
        if (current != null) PatchworkCoordinatorRegistry.unregister(current);
        closed.set(true);
    }
}
