package com.alechilles.patchwork.embedded;

import com.alechilles.patchwork.coordinator.PatchworkCoordinatorBridge;
import com.alechilles.patchwork.engine.PatchMacroRegistry;
import com.alechilles.patchwork.engine.PatchOperation;
import com.alechilles.patchwork.reload.HytalePatchTargetAdapter;
import com.alechilles.patchwork.reload.PatchReloadTracker;
import com.alechilles.patchwork.reload.PatchReloadCoordinator;
import com.hypixel.hytale.server.core.asset.LoadAssetEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Elected runtime composition.  It owns only the lease-scoped contribution
 * multiplexers and collaborators supplied by its bootstrap; hosts that lose election
 * are fenced before their callbacks can be admitted again.
 */
public final class PatchworkRuntimeHost implements PatchworkCoordinatorBridge {
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(3);
    private final Path generatedRoot;
    private final PatchMacroRegistry macros = new PatchMacroRegistry();
    private final PatchReloadTracker tracker = new PatchReloadTracker();
    private final Object gate = new Object();
    private final EarlyLoadRegistrar earlyLoadRegistrar;
    private Map<String, ContributionBridge> contributions = Map.of();
    private Map<RouteKey, String> observationRoutes = Map.of();
    private boolean accepting;
    private long epoch;
    private int inFlight;
    private long registeredEpoch = Long.MIN_VALUE;
    private Consumer<LoadAssetEvent> registeredEarlyLoad;
    private EarlyLoadRegistration earlyLoadRegistration;
    private PatchReloadCoordinator reloadCoordinator;

    /** Creates a host whose startup action is invoked only after election. */
    public PatchworkRuntimeHost(Path generatedRoot, Runnable startupAction) {
        this(generatedRoot, new EarlyLoadRegistrar() {
            @Override public EarlyLoadRegistration register(long epoch, Consumer<LoadAssetEvent> callback) { return () -> { }; }
            @Override public void execute(long epoch, PatchMacroRegistry macros, LoadAssetEvent event, EpochActionGate actionGate) { actionGate.execute(startupAction); }
        });
    }

    /** Creates a host with its elected-only early-load registration collaborator. */
    PatchworkRuntimeHost(Path generatedRoot, EarlyLoadRegistrar earlyLoadRegistrar) {
        this.generatedRoot = Objects.requireNonNull(generatedRoot).toAbsolutePath().normalize();
        this.earlyLoadRegistrar = Objects.requireNonNull(earlyLoadRegistrar);
    }

    @Override public void activate(long value) {
        synchronized (gate) { epoch = value; accepting = true; }
        PatchReloadCoordinator coordinator = reloadCoordinator;
        if (coordinator != null) coordinator.activate(value);
    }

    @Override public void fence(long value) {
        synchronized (gate) {
            if (value >= epoch) {
                accepting = false;
                tracker.cancelAll("Runtime fenced.");
                observationRoutes = Map.of();
            }
        }
        PatchReloadCoordinator coordinator = reloadCoordinator;
        if (coordinator != null) coordinator.revoke(value);
    }

    @Override public void stopAcceptingAndDrain(long value) {
        fence(value);
        PatchReloadCoordinator coordinator = reloadCoordinator;
        if (coordinator != null && !coordinator.drain(DRAIN_TIMEOUT)) {
            throw new IllegalStateException("Timed out draining the fenced Patchwork reload coordinator.");
        }
        drain(DRAIN_TIMEOUT);
        unregisterEarlyLoad();
    }

    @Override public void deactivate(long value) {
        fence(value);
        drain(DRAIN_TIMEOUT);
        unregisterEarlyLoad();
    }

    @Override public void start(long value) {
        synchronized (gate) {
            if (!accepting || value != epoch) throw new IllegalStateException("Runtime host is not active for this epoch.");
            }
        synchronized (gate) {
            if (registeredEpoch == value) return;
            Consumer<LoadAssetEvent> callback = event -> runEarlyLoad(value, event);
            EarlyLoadRegistration registration = Objects.requireNonNull(earlyLoadRegistrar.register(value, callback), "Early-load registration handle is required.");
            registeredEpoch = value;
            registeredEarlyLoad = callback;
            earlyLoadRegistration = registration;
        }
    }

    @Override public String generatedPatchRoot() { return generatedRoot.toString(); }
    @Override public String expandOperationJson(String operationJson) {
        PatchOperation operation = PatchOperation.parseHostOperation(JsonParser.parseString(operationJson).getAsJsonObject(), "embedded");
        JsonArray values = new JsonArray(); for (PatchOperation expanded : macros.expand(operation)) values.add(expanded.toJson());
        return values.toString();
    }

    /** Returns the one narrow reload adapter supplied to a PatchReloadCoordinator. */
    public HytalePatchTargetAdapter targetAdapter() {
        return new HytalePatchTargetAdapter("patchwork-host-contributions", this::supports, this::reload);
    }

    /** Builds a coordinator over this host's exact tracker and dynamic contribution multiplexer. */
    PatchReloadCoordinator reloadCoordinator(Duration timeout) {
        HytalePatchTargetAdapter unavailableBuiltIn = new HytalePatchTargetAdapter("patchwork-unavailable", ignored -> false, ignored -> HytalePatchTargetAdapter.AdapterReply.rejected("No built-in reload route."));
        synchronized (gate) {
            if (reloadCoordinator == null) reloadCoordinator = new PatchReloadCoordinator(generatedRoot, tracker, unavailableBuiltIn, List.of(targetAdapter()), timeout);
            if (accepting) reloadCoordinator.activate(epoch);
            return reloadCoordinator;
        }
    }

    @Override public void replayContributions(long value, List<Map<String, ?>> descriptors) {
        Map<String, ContributionBridge> proposed = parseSnapshot(descriptors);
        synchronized (gate) {
            requireLease(value);
            accepting = false;
        }
        try {
            drain(DRAIN_TIMEOUT);
            synchronized (gate) {
                List<PatchMacroRegistry.MacroRegistration> proposedMacros = new ArrayList<>();
                for (ContributionBridge bridge : proposed.values()) {
                    for (String macroId : bridge.macroIds()) proposedMacros.add(new PatchMacroRegistry.MacroRegistration(bridge.host(), macroId, operation -> bridge.expand(macroId, operation)));
                }
                macros.replace(proposedMacros);
                contributions = Map.copyOf(proposed);
                observationRoutes = Map.of();
                accepting = true;
            }
        } catch (RuntimeException failure) {
            // The previous immutable macro/contribution snapshot remains installed; keep it usable.
            synchronized (gate) { accepting = value == epoch; }
            throw failure;
        }
    }

    @Override public boolean recordObservation(Map<String, ?> map) {
        try {
            long eventEpoch = ((Number) map.get("epoch")).longValue();
            String adapter = requiredText(map, "adapterId");
            String target = requiredText(map, "target");
            String hash = requiredText(map, "expectedHash");
            PatchReloadTracker.Outcome outcome = PatchReloadTracker.Outcome.valueOf(requiredText(map, "outcome"));
            String token;
            RouteKey key = new RouteKey(eventEpoch, adapter, target, hash);
            synchronized (gate) {
                if (!accepting || eventEpoch != epoch) return false;
                token = observationRoutes.get(key);
            }
            boolean recorded = token != null && tracker.record(new PatchReloadTracker.Observation(token, eventEpoch, target, hash, outcome));
            if (recorded) removeRoute(key);
            return recorded;
        } catch (RuntimeException invalid) { return false; }
    }

    private boolean supports(HytalePatchTargetAdapter.ReloadTarget target) {
        ContributionBridge bridge = select(target.target());
        return bridge != null && bridge.supports(target.target(), target.family().name());
    }

    private HytalePatchTargetAdapter.AdapterReply reload(HytalePatchTargetAdapter.ReloadTarget target) throws Exception {
        ContributionBridge bridge = select(target.target());
        if (bridge == null) return HytalePatchTargetAdapter.AdapterReply.rejected("No host adapter accepts target.");
        String adapterId = bridge.adapterId(target.target());
        RouteKey route = new RouteKey(target.epoch(), adapterId, target.target(), target.expectedHash());
        synchronized (gate) { requireLease(target.epoch()); Map<RouteKey, String> next = new LinkedHashMap<>(observationRoutes); next.put(route, target.token()); observationRoutes = Map.copyOf(next); }
        PatchworkReloadResult result;
        try { result = bridge.reload(adapterId, target).toCompletableFuture().get(DRAIN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (Exception failure) { removeRoute(route); throw failure; }
        if (!adapterId.equals(result.adapterId())) { removeRoute(route); return HytalePatchTargetAdapter.AdapterReply.rejected("Host adapter returned a mismatched adapter ID."); }
        if (!result.failures().isEmpty()) { removeRoute(route); return HytalePatchTargetAdapter.AdapterReply.rejected(String.join("; ", result.failures())); }
        if (result.restartRequiredTargets().contains(target.target())) { removeRoute(route); return HytalePatchTargetAdapter.AdapterReply.restartRequired("Host adapter requires restart."); }
        if (!result.reloadedTargets().contains(target.target())) { removeRoute(route); return HytalePatchTargetAdapter.AdapterReply.rejected("Host adapter did not accept target."); }
        return HytalePatchTargetAdapter.AdapterReply.confirmed();
    }

    private void removeRoute(RouteKey route) { synchronized (gate) { Map<RouteKey, String> next = new LinkedHashMap<>(observationRoutes); next.remove(route); observationRoutes = Map.copyOf(next); } }

    private ContributionBridge select(String target) {
        synchronized (gate) {
            if (!accepting) return null;
            for (ContributionBridge bridge : contributions.values()) if (bridge.supports(target, "CUSTOM")) return bridge;
            return null;
        }
    }

    private void drain(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (gate) {
            while (inFlight != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("Timed out draining host contribution invocations.");
                try { gate.wait(Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted draining host contributions.", interrupted); }
            }
        }
    }

    private boolean isActive(long expectedEpoch) {
        synchronized (gate) { return accepting && epoch == expectedEpoch; }
    }

    private void runEarlyLoad(long expectedEpoch, LoadAssetEvent event) {
        synchronized (gate) {
            if (!accepting || epoch != expectedEpoch) return;
            inFlight++;
        }
        try { earlyLoadRegistrar.execute(expectedEpoch, macros, event, action -> runEpochAction(expectedEpoch, action)); }
        finally { synchronized (gate) { inFlight--; gate.notifyAll(); } }
    }

    private boolean runEpochAction(long expectedEpoch, Runnable action) {
        synchronized (gate) {
            if (!accepting || epoch != expectedEpoch) return false;
            action.run();
            return true;
        }
    }

    private void unregisterEarlyLoad() {
        EarlyLoadRegistration registration;
        synchronized (gate) {
            registration = earlyLoadRegistration;
        }
        if (registration == null) return;
        registration.unregister();
        synchronized (gate) {
            if (earlyLoadRegistration != registration) return;
            earlyLoadRegistration = null;
            registeredEarlyLoad = null;
            registeredEpoch = Long.MIN_VALUE;
        }
    }

    /** Test-only callback probe; production event registries own callback delivery. */
    void runRegisteredEarlyLoadForTest() {
        Consumer<LoadAssetEvent> callback;
        synchronized (gate) { callback = registeredEarlyLoad; }
        if (callback != null) callback.accept(null);
    }

    private void requireLease(long value) {
        if (!accepting || value != epoch) throw new IllegalStateException("Runtime host is not active for this epoch.");
    }

    private Map<String, ContributionBridge> parseSnapshot(List<Map<String, ?>> descriptors) {
        Map<String, ContributionBridge> parsed = new LinkedHashMap<>();
        for (Map<String, ?> descriptor : descriptors) {
            ContributionBridge bridge = new ContributionBridge(descriptor);
            if (parsed.putIfAbsent(bridge.token(), bridge) != null) throw new IllegalArgumentException("Duplicate contribution token.");
        }
        return parsed;
    }

    private static String requiredText(Map<String, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return text;
    }

    private record RouteKey(long epoch, String adapterId, String target, String hash) { }

    /** One guarded opaque foreign contribution bridge. */
    private final class ContributionBridge {
        private final String token;
        private final String host;
        private final List<String> macroIds;
        private final List<String> adapterIds;
        private final Object receiver;

        private ContributionBridge(Map<String, ?> descriptor) {
            token = requiredText(descriptor, "contributionToken");
            host = requiredText(descriptor, "hostPluginIdentifier");
            receiver = Objects.requireNonNull(descriptor.get("bridge"), "bridge");
            macroIds = copyTexts(descriptor.get("macroIds"), "macroIds");
            adapterIds = copyTexts(descriptor.get("adapterIds"), "adapterIds");
        }
        String token() { return token; }
        String host() { return host; }
        List<String> macroIds() { return macroIds; }
        String adapterId(String target) {
            for (String adapterId : adapterIds) if (supportsAdapter(adapterId, target)) return adapterId;
            throw new IllegalStateException("No registered adapter supports target.");
        }
        private boolean supportsAdapter(String adapterId, String target) { return guarded(() -> (boolean) receiver.getClass().getMethod("supports", String.class, String.class, String.class).invoke(receiver, adapterId, target, "CUSTOM")); }
        boolean supports(String target, String family) { for (String adapterId : adapterIds) if (guarded(() -> (boolean) receiver.getClass().getMethod("supports", String.class, String.class, String.class).invoke(receiver, adapterId, target, family))) return true; return false; }
        CompletionStage<PatchworkReloadResult> reload(String adapterId, HytalePatchTargetAdapter.ReloadTarget target) {
            Map<String, Object> request = Map.of("epoch", target.epoch(), "target", target.target(), "expectedHash", target.expectedHash(), "removal", target.removal());
            Object stage = guardedAsync(() -> receiver.getClass().getMethod("reload", String.class, Map.class).invoke(receiver, adapterId, request));
            if (!(stage instanceof CompletionStage<?> completion)) throw new IllegalStateException("Host reload did not return CompletionStage.");
            return completion.thenApply(this::decodeReloadResult);
        }
        List<PatchOperation> expand(String id, PatchOperation operation) {
            String json = guarded(() -> (String) receiver.getClass().getMethod("expand", String.class, String.class).invoke(receiver, id, operation.toJson().toString()));
            JsonArray values = JsonParser.parseString(new String(json.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).getAsJsonArray();
            List<PatchOperation> result = new ArrayList<>();
            for (var value : values) result.add(PatchOperation.parseHostOperation(value.getAsJsonObject(), operation.id()));
            return List.copyOf(result);
        }
        private PatchworkReloadResult decodeReloadResult(Object value) {
            if (!(value instanceof Map<?, ?> raw)) throw new IllegalStateException("Host reload result is not a map.");
            return new PatchworkReloadResult(requiredText((Map<String, ?>) raw, "adapterId"), copyTexts(raw.get("reloadedTargets"), "reloadedTargets"), copyTexts(raw.get("restartRequiredTargets"), "restartRequiredTargets"), copyTexts(raw.get("failures"), "failures"));
        }
        private <T> T guarded(Invocation<T> invocation) {
            synchronized (gate) { if (!accepting) throw new IllegalStateException("Host contribution is closed."); inFlight++; }
            try { return invocation.call(); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Host contribution bridge invocation failed.", failure); }
            finally { synchronized (gate) { inFlight--; gate.notifyAll(); } }
        }
        private Object guardedAsync(Invocation<Object> invocation) {
            synchronized (gate) { if (!accepting) throw new IllegalStateException("Host contribution is closed."); inFlight++; }
            try { Object result = invocation.call(); if (!(result instanceof CompletionStage<?> stage)) throw new IllegalStateException("Host reload did not return CompletionStage."); return stage.whenComplete((ignored, failure) -> releaseInvocation()); }
            catch (ReflectiveOperationException | RuntimeException failure) { releaseInvocation(); throw failure instanceof RuntimeException runtime ? runtime : new IllegalStateException("Host contribution bridge invocation failed.", failure); }
        }
        private void releaseInvocation() { synchronized (gate) { inFlight--; gate.notifyAll(); } }
    }

    private static List<String> copyTexts(Object value, String key) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Invalid " + key);
        List<String> copied = new ArrayList<>();
        for (Object entry : list) { if (!(entry instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Invalid " + key); copied.add(text); }
        return List.copyOf(copied);
    }

    @FunctionalInterface private interface Invocation<T> { T call() throws ReflectiveOperationException; }

    /** Elected-only early-load callback registration seam. */
    interface EarlyLoadRegistrar {
        EarlyLoadRegistration register(long epoch, Consumer<LoadAssetEvent> callback);
        default void execute(long epoch, PatchMacroRegistry macros, LoadAssetEvent event, EpochActionGate actionGate) { }
    }

    /** Narrow event-registry handle retained only for the active ownership epoch. */
    @FunctionalInterface interface EarlyLoadRegistration { void unregister(); }

    /** Runs a publication atomically with the active-epoch check. */
    @FunctionalInterface interface EpochActionGate { boolean execute(Runnable action); }
}
