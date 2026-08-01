package com.alechilles.patchwork.coordinator;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Serializes the single process-wide runtime owner and its publication lease. */
public final class PatchworkCoordinatorRegistry {
    public static final String REGISTRY_PROPERTY = "com.alechilles.patchwork.coordinator.registry";
    public static final int COORDINATOR_ABI = 1;

    private final Map<PatchworkRegistrationToken, PatchworkRuntimeCandidate> candidates = new LinkedHashMap<>();
    private PatchworkRegistrationToken activeToken;
    private long epoch;
    private boolean transitioning;
    private boolean publishing;
    private boolean failedClosed;

    private PatchworkCoordinatorRegistry() { }

    static PatchworkCoordinatorRegistry current() {
        synchronized (System.getProperties()) {
            Object state = System.getProperties().get(REGISTRY_PROPERTY);
            if (state instanceof PatchworkCoordinatorRegistry registry) return registry;
            if (state != null) throw new IllegalStateException("Foreign coordinator registry owns the property");
            PatchworkCoordinatorRegistry registry = new PatchworkCoordinatorRegistry();
            System.getProperties().put(REGISTRY_PROPERTY, registry);
            return registry;
        }
    }

    static void clearForTests() {
        synchronized (System.getProperties()) { System.getProperties().remove(REGISTRY_PROPERTY); }
    }

    synchronized PatchworkRegistrationToken registerCandidate(PatchworkRuntimeCandidate candidate) {
        checkAvailable();
        PatchworkRegistrationToken token = new PatchworkRegistrationToken();
        candidates.put(token, candidate);
        try {
            PatchworkRegistrationToken winner = selectWinnerForReplacement(candidate.providerId(), token);
            if (winner != activeToken) handoff(activeToken, winner, token);
            commitProviderReplacement(candidate.providerId(), token);
            return token;
        } catch (RuntimeException failure) {
            discard(token);
            throw failure;
        }
    }

    synchronized void unregister(PatchworkRegistrationToken token) {
        checkAvailable();
        if (!candidates.containsKey(token)) return;
        if (token != activeToken) { candidates.remove(token); return; }
        handoff(token, selectWinnerExcluding(token), null);
    }

    synchronized boolean publish(PatchworkRegistrationToken token) {
        if (failedClosed || transitioning || publishing || token == null || token != activeToken) return false;
        PatchworkRuntimeCandidate active = activeCandidate();
        if (active == null) return false;
        publishing = true;
        try { return active.bridge().publish(epoch); }
        finally { publishing = false; }
    }

    synchronized String localActiveProviderId() {
        PatchworkRuntimeCandidate active = activeCandidate();
        return active == null ? null : active.providerId();
    }

    synchronized PatchworkRegistrationToken activeToken() { return activeToken; }
    synchronized int candidateCount() { return candidates.size(); }
    synchronized long ownershipEpoch() { return epoch; }

    private void handoff(PatchworkRegistrationToken oldToken, PatchworkRegistrationToken newToken,
                         PatchworkRegistrationToken stagedToken) {
        transitioning = true;
        try {
            if (oldToken != null && !retire(oldToken)) {
                discard(stagedToken);
                failClosed("Current owner could not be safely retired");
            }
            activeToken = null;
            if (newToken == null) {
                candidates.remove(oldToken);
                return;
            }
            if (activate(newToken)) {
                if (stagedToken == null) candidates.remove(oldToken);
                removeOtherProviderEntries(newToken);
                return;
            }
            boolean cleaned = cleanupSucceeded(newToken);
            discard(newToken);
            if (!cleaned) failClosed("Replacement cleanup failed");
            if (oldToken != null && candidates.containsKey(oldToken) && activate(oldToken)) {
                throw failure("Replacement activation failed");
            }
            if (oldToken != null) candidates.remove(oldToken);
            activateNextEligible();
            throw failure("Replacement activation failed");
        } finally {
            transitioning = false;
        }
    }

    private boolean retire(PatchworkRegistrationToken token) {
        PatchworkRuntimeCandidate old = candidates.get(token);
        if (old == null) return false;
        try {
            old.bridge().fence(epoch);
            old.bridge().stopAcceptingAndDrain(epoch);
            old.bridge().deactivate(epoch);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean activate(PatchworkRegistrationToken token) {
        PatchworkRuntimeCandidate candidate = candidates.get(token);
        if (candidate == null) return false;
        long nextEpoch = ++epoch;
        try {
            candidate.bridge().activate(nextEpoch);
            candidate.bridge().start(nextEpoch);
            activeToken = token;
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean cleanupSucceeded(PatchworkRegistrationToken token) {
        PatchworkRuntimeCandidate candidate = candidates.get(token);
        if (candidate == null) return true;
        try {
            candidate.bridge().fence(epoch);
            candidate.bridge().stopAcceptingAndDrain(epoch);
            candidate.bridge().deactivate(epoch);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void activateNextEligible() {
        PatchworkRegistrationToken next;
        while ((next = selectWinner()) != null) {
            if (activate(next)) return;
            if (!cleanupSucceeded(next)) failClosed("Recovery cleanup failed");
            candidates.remove(next);
        }
    }

    private void removeOtherProviderEntries(PatchworkRegistrationToken kept) {
        String providerId = candidates.get(kept).providerId();
        candidates.entrySet().removeIf(entry -> entry.getKey() != kept && entry.getValue().providerId().equals(providerId));
    }

    private void commitProviderReplacement(String providerId, PatchworkRegistrationToken kept) {
        candidates.entrySet().removeIf(entry -> entry.getKey() != kept && entry.getValue().providerId().equals(providerId));
    }

    private void discard(PatchworkRegistrationToken token) {
        if (token != null && token != activeToken) candidates.remove(token);
    }

    private PatchworkRegistrationToken selectWinner() { return selectWinnerExcluding(null); }

    private PatchworkRegistrationToken selectWinnerForReplacement(String providerId, PatchworkRegistrationToken replacement) {
        return candidates.entrySet().stream()
                .filter(entry -> entry.getKey() == replacement || !entry.getValue().providerId().equals(providerId))
                .filter(entry -> entry.getValue().compatibleWith(COORDINATOR_ABI))
                .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private PatchworkRegistrationToken selectWinnerExcluding(PatchworkRegistrationToken excluded) {
        return candidates.entrySet().stream()
                .filter(entry -> entry.getKey() != excluded && entry.getValue().compatibleWith(COORDINATOR_ABI))
                .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private PatchworkRuntimeCandidate activeCandidate() { return activeToken == null ? null : candidates.get(activeToken); }

    private void checkAvailable() {
        if (failedClosed) throw failure("Coordinator is fail-closed after unsafe lifecycle cleanup");
        if (transitioning || publishing) throw failure("Coordinator lifecycle callback cannot reenter election");
    }

    private void failClosed(String message) {
        activeToken = null;
        failedClosed = true;
        throw failure(message);
    }

    private static IllegalStateException failure(String message) { return new IllegalStateException(message); }

    /** JDK-only foreign registration. Failures are reported as {@link IllegalStateException}. */
    public static String register(Map<String, ?> descriptor) { return withRegistry("register", descriptor); }
    public static boolean unregister(String token) { return Boolean.parseBoolean(withRegistry("unregister", token)); }
    public static boolean publish(String token) { return Boolean.parseBoolean(withRegistry("publish", token)); }
    public static String activeProviderId() { return withRegistry("activeProviderId", null); }

    private static String withRegistry(String operation, Object value) {
        Object state = installedRegistry();
        if (!(state instanceof PatchworkCoordinatorRegistry registry)) return invokeForeign(state, operation, value);
        return switch (operation) {
            case "register" -> registry.registerCandidate(fromDescriptor((Map<String, ?>) value)).toString();
            case "unregister" -> Boolean.toString(registry.unregisterExternal((String) value));
            case "publish" -> Boolean.toString(registry.publishExternal((String) value));
            default -> registry.localActiveProviderId();
        };
    }

    private static Object installedRegistry() {
        synchronized (System.getProperties()) {
            Object state = System.getProperties().get(REGISTRY_PROPERTY);
            if (state == null) { state = new PatchworkCoordinatorRegistry(); System.getProperties().put(REGISTRY_PROPERTY, state); }
            return state;
        }
    }

    private synchronized boolean unregisterExternal(String text) {
        PatchworkRegistrationToken token = findToken(this, text);
        if (token == null) return false;
        unregister(token);
        return true;
    }

    private synchronized boolean publishExternal(String text) { return publish(findToken(this, text)); }

    private static PatchworkRegistrationToken findToken(PatchworkCoordinatorRegistry registry, String text) {
        return registry.candidates.keySet().stream().filter(token -> token.toString().equals(text)).findFirst().orElse(null);
    }

    private static String invokeForeign(Object state, String op, Object value) {
        try {
            Method method = value == null ? state.getClass().getMethod(op)
                    : state.getClass().getMethod(op, value instanceof Map ? Map.class : String.class);
            Object result = value == null ? method.invoke(null) : method.invoke(null, value);
            return String.valueOf(result);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Incompatible coordinator registry", exception);
        }
    }

    private static PatchworkRuntimeCandidate fromDescriptor(Map<String, ?> map) {
        require(map, "providerId", String.class); require(map, "origin", String.class); require(map, "runtimeVersion", String.class); require(map, "coordinatorAbi", Number.class);
        require(map, "providerPluginId", String.class); require(map, "providerPluginVersion", String.class); require(map, "sourceJarPath", Path.class); require(map, "providerDataRoot", Path.class);
        Object bridge = require(map, "bridge", Object.class);
        return new PatchworkRuntimeCandidate((String) map.get("providerId"), PatchworkRuntimeOrigin.valueOf((String) map.get("origin")), (String) map.get("runtimeVersion"), exactCoordinatorAbi(map),
                (String) map.get("providerPluginId"), (String) map.get("providerPluginVersion"), (Path) map.get("sourceJarPath"), (Path) map.get("providerDataRoot"), new ReflectiveBridge(bridge));
    }

    private static <T> T require(Map<String, ?> map, String key, Class<T> type) {
        Object value = map.get(key);
        if (!type.isInstance(value) || (value instanceof String text && text.strip().isEmpty())) throw new IllegalArgumentException("Invalid coordinator descriptor field: " + key);
        return type.cast(value);
    }

    private static int exactCoordinatorAbi(Map<String, ?> map) {
        Number number = require(map, "coordinatorAbi", Number.class);
        try { return new BigDecimal(number.toString()).intValueExact(); }
        catch (NumberFormatException | ArithmeticException exception) { throw new IllegalArgumentException("Invalid coordinator descriptor field: coordinatorAbi", exception); }
    }

    private static final class ReflectiveBridge implements PatchworkCoordinatorBridge {
        private final Object receiver;
        private final Method fence, drain, deactivate, activate, start, publish;

        ReflectiveBridge(Object receiver) {
            this.receiver = Objects.requireNonNull(receiver);
            try {
                Class<?> type = receiver.getClass();
                fence = method(type, "fence", void.class); drain = method(type, "stopAcceptingAndDrain", void.class);
                deactivate = method(type, "deactivate", void.class); activate = method(type, "activate", void.class);
                start = method(type, "start", void.class); publish = method(type, "publish", boolean.class);
            } catch (ReflectiveOperationException exception) { throw new IllegalArgumentException("Invalid coordinator bridge", exception); }
        }

        public void fence(long value) { invoke(fence, value, Void.class); }
        public void stopAcceptingAndDrain(long value) { invoke(drain, value, Void.class); }
        public void deactivate(long value) { invoke(deactivate, value, Void.class); }
        public void activate(long value) { invoke(activate, value, Void.class); }
        public void start(long value) { invoke(start, value, Void.class); }
        public boolean publish(long value) { return (Boolean) invoke(publish, value, Boolean.class); }

        private static Method method(Class<?> type, String name, Class<?> result) throws ReflectiveOperationException {
            Method method = type.getMethod(name, long.class);
            if (method.getReturnType() != result) throw new NoSuchMethodException(name);
            return method;
        }

        private Object invoke(Method method, long value, Class<?> expected) {
            try {
                Object result = method.invoke(receiver, value);
                if (expected == Void.class || expected.isInstance(result)) return result;
                throw new IllegalStateException("Invalid bridge return: " + method.getName());
            } catch (ReflectiveOperationException exception) { throw new IllegalStateException("Bridge lifecycle failure: " + method.getName(), exception); }
        }
    }
}
