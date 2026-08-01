package com.alechilles.patchwork.coordinator;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/** Serializes the single process-wide runtime owner and its publication lease. */
public final class PatchworkCoordinatorRegistry {
    public static final String REGISTRY_PROPERTY = "com.alechilles.patchwork.coordinator.registry";
    public static final int COORDINATOR_ABI = 1;

    private final Map<PatchworkRegistrationToken, PatchworkRuntimeCandidate> candidates = new LinkedHashMap<>();
    private Map<String, Map<String, ?>> contributions = Map.of();
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

    synchronized PatchworkRegistrationToken registerCandidate(PatchworkRuntimeCandidate candidate) { return registerCandidate(candidate, null); }
    synchronized PatchworkRegistrationToken registerCandidate(PatchworkRuntimeCandidate candidate, PatchworkRegistrationToken replacementToken) {
        checkAvailable();
        PatchworkRegistrationToken incumbent = providerToken(candidate.providerId());
        if (incumbent != replacementToken) throw failure("Replacement token must exactly match the provider incumbent");
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
        try { return active.bridge().publish(epoch); } catch (RuntimeException failure) { return false; }
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
            if (oldToken != null && candidates.containsKey(oldToken)) {
                if (activate(oldToken)) throw failure("Replacement activation failed");
                if (!cleanupSucceeded(oldToken)) failClosed("Prior recovery cleanup failed");
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
            candidate.bridge().replayContributions(nextEpoch, contributionSnapshot());
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

    private PatchworkRegistrationToken providerToken(String providerId) {
        return candidates.entrySet().stream().filter(entry -> entry.getValue().providerId().equals(providerId)).map(Map.Entry::getKey).findFirst().orElse(null);
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
    private List<Map<String, ?>> contributionSnapshot() { return List.copyOf(contributions.values()); }

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
    /** Registers a JDK-map contribution descriptor that is replayed to every elected runtime. */
    public static String registerContribution(Map<String, ?> descriptor) { return withRegistry("registerContribution", descriptor); }
    public static boolean unregisterContribution(String token) { return Boolean.parseBoolean(withRegistry("unregisterContribution", token)); }
    public static String generatedPatchRoot() { return withRegistry("generatedPatchRoot", null); }
    public static boolean recordObservation(Map<String, ?> observation) { return Boolean.parseBoolean(withRegistry("recordObservation", observation)); }
    /** Expands one UTF-8/JSON operation through the elected runtime. */
    public static String expandOperationJson(String operationJson) { return withRegistry("expandOperationJson", operationJson); }

    private static String withRegistry(String operation, Object value) {
        Object state = installedRegistry();
        if (!(state instanceof PatchworkCoordinatorRegistry registry)) return invokeForeign(state, operation, value);
        return switch (operation) {
            case "register" -> registry.registerExternal((Map<String, ?>) value);
            case "unregister" -> Boolean.toString(registry.unregisterExternal((String) value));
            case "publish" -> Boolean.toString(registry.publishExternal((String) value));
            case "registerContribution" -> registry.registerContributionExternal((Map<String, ?>) value);
            case "unregisterContribution" -> Boolean.toString(registry.unregisterContributionExternal((String) value));
            case "generatedPatchRoot" -> registry.generatedPatchRootExternal();
            case "recordObservation" -> Boolean.toString(registry.recordObservationExternal((Map<String, ?>) value));
            case "expandOperationJson" -> registry.expandOperationJsonExternal((String) value);
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

    private synchronized String registerContributionExternal(Map<String, ?> descriptor) {
        checkAvailable();
        Map<String, ?> canonical = canonicalContribution(descriptor);
        rejectContributionConflicts(canonical);
        String token = new PatchworkRegistrationToken().toString();
        Map<String, Map<String, ?>> proposed = new LinkedHashMap<>(contributions);
        Map<String, Object> registered = new LinkedHashMap<>(canonical); registered.put("contributionToken", token);
        proposed.put(token, Map.copyOf(registered));
        applyContributionSnapshot(proposed);
        contributions = Map.copyOf(proposed);
        return token;
    }

    private synchronized boolean unregisterContributionExternal(String token) {
        checkAvailable();
        Map<String, ?> removed = contributions.get(token);
        if (removed == null) return false;
        Map<String, Map<String, ?>> proposed = new LinkedHashMap<>(contributions); proposed.remove(token);
        applyContributionSnapshot(proposed);
        contributions = Map.copyOf(proposed);
        return true;
    }

    private synchronized String generatedPatchRootExternal() {
        PatchworkRuntimeCandidate active = activeCandidate();
        return active == null ? null : active.bridge().generatedPatchRoot();
    }

    private synchronized boolean recordObservationExternal(Map<String, ?> observation) {
        PatchworkRuntimeCandidate active = activeCandidate();
        return active != null && active.bridge().recordObservation(Map.copyOf(observation));
    }
    private synchronized String expandOperationJsonExternal(String operationJson) {
        if (operationJson == null || operationJson.isBlank()) throw new IllegalArgumentException("Operation JSON is required.");
        PatchworkRuntimeCandidate active = activeCandidate(); if (active == null) throw new IllegalStateException("No active Patchwork runtime is available.");
        return active.bridge().expandOperationJson(operationJson);
    }

    private void replayActiveContributions() { applyContributionSnapshot(contributions); }
    private void applyContributionSnapshot(Map<String, Map<String, ?>> snapshot) {
        PatchworkRuntimeCandidate active = activeCandidate();
        if (active == null) return;
        try { active.bridge().replayContributions(epoch, List.copyOf(snapshot.values())); }
        catch (RuntimeException failure) {
            try { active.bridge().replayContributions(epoch, contributionSnapshot()); }
            catch (RuntimeException rollbackFailure) { failClosed("Contribution replay rollback failed"); }
            throw failure;
        }
    }

    private static Map<String, ?> canonicalContribution(Map<String, ?> descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        require(descriptor, "hostPluginIdentifier", String.class); require(descriptor, "contributionVersion", String.class);
        require(descriptor, "bridge", Object.class); require(descriptor, "macroIds", List.class); require(descriptor, "adapterIds", List.class);
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("hostPluginIdentifier", require(descriptor, "hostPluginIdentifier", String.class));
        copy.put("contributionVersion", require(descriptor, "contributionVersion", String.class));
        copy.put("bridge", require(descriptor, "bridge", Object.class));
        copy.put("macroIds", copyTextList(descriptor.get("macroIds"), "macroIds"));
        copy.put("adapterIds", copyTextList(descriptor.get("adapterIds"), "adapterIds"));
        return Map.copyOf(copy);
    }
    private void rejectContributionConflicts(Map<String, ?> descriptor) {
        String host = (String) descriptor.get("hostPluginIdentifier"); String version = (String) descriptor.get("contributionVersion");
        List<?> newMacros = (List<?>) descriptor.get("macroIds"); List<?> newAdapters = (List<?>) descriptor.get("adapterIds");
        for (Map<String, ?> existing : contributions.values()) {
            List<?> oldAdapters = (List<?>) existing.get("adapterIds");
            for (Object adapter : newAdapters) if (oldAdapters.contains(adapter)) throw new IllegalArgumentException("Duplicate Patchwork target adapter ID: " + adapter);
            if (host.equals(existing.get("hostPluginIdentifier")) && version.equals(existing.get("contributionVersion"))) {
                List<?> oldMacros = (List<?>) existing.get("macroIds");
                for (Object macro : newMacros) if (oldMacros.contains(macro)) throw new IllegalArgumentException("Duplicate Patchwork macro tuple: " + host + ":" + version + ":" + macro);
            }
        }
        if (newMacros.stream().distinct().count() != newMacros.size() || newAdapters.stream().distinct().count() != newAdapters.size()) throw new IllegalArgumentException("Contribution contains duplicate macro or adapter identifiers.");
    }
    private static List<String> copyTextList(Object value, String field) {
        if (!(value instanceof List<?> source)) throw new IllegalArgumentException("Invalid contribution field: " + field);
        List<String> copy = new ArrayList<>();
        for (Object entry : source) {
            if (!(entry instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Invalid contribution field: " + field);
            copy.add(text);
        }
        return List.copyOf(copy);
    }

    /** Resolves and validates a public replacement handle while holding the election monitor. */
    private synchronized String registerExternal(Map<String, ?> descriptor) {
        PatchworkRuntimeCandidate candidate = fromDescriptor(descriptor);
        PatchworkRegistrationToken replacement = replacementToken(descriptor);
        return registerCandidate(candidate, replacement).toString();
    }

    private static PatchworkRegistrationToken findToken(PatchworkCoordinatorRegistry registry, String text) {
        return registry.candidates.keySet().stream().filter(token -> token.toString().equals(text)).findFirst().orElse(null);
    }

    private static String invokeForeign(Object state, String op, Object value) {
        try {
            Method method = value == null ? state.getClass().getMethod(op)
                    : state.getClass().getMethod(op, value instanceof Map ? Map.class : String.class);
            Object result = value == null ? method.invoke(null) : method.invoke(null, value);
            return result == null ? null : String.valueOf(result);
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

    private PatchworkRegistrationToken replacementToken(Map<String, ?> descriptor) {
        Object value = descriptor.get("replacementToken");
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Invalid coordinator descriptor field: replacementToken");
        PatchworkRegistrationToken token = findToken(this, text);
        if (token == null) throw new IllegalStateException("Unknown replacement registration token");
        return token;
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
        private final Method fence, drain, deactivate, activate, start, publish, replay, generatedRoot, observation;

        ReflectiveBridge(Object receiver) {
            this.receiver = Objects.requireNonNull(receiver);
            try {
                Class<?> type = receiver.getClass();
                fence = method(type, "fence", void.class); drain = method(type, "stopAcceptingAndDrain", void.class);
                deactivate = method(type, "deactivate", void.class); activate = method(type, "activate", void.class);
                start = method(type, "start", void.class); publish = method(type, "publish", boolean.class);
                replay = optionalContributionMethod(type, "replayContributions"); generatedRoot = optionalNoArgMethod(type, "generatedPatchRoot", String.class);
                observation = optionalContributionMethod(type, "recordObservation");
            } catch (ReflectiveOperationException exception) { throw new IllegalArgumentException("Invalid coordinator bridge", exception); }
        }

        public void fence(long value) { invoke(fence, value, Void.class); }
        public void stopAcceptingAndDrain(long value) { invoke(drain, value, Void.class); }
        public void deactivate(long value) { invoke(deactivate, value, Void.class); }
        public void activate(long value) { invoke(activate, value, Void.class); }
        public void start(long value) { invoke(start, value, Void.class); }
        public boolean publish(long value) { return (Boolean) invoke(publish, value, Boolean.class); }
        public void replayContributions(long value, List<Map<String, ?>> contributions) { if (replay != null) invoke(replay, value, contributions, Void.class); }
        public String generatedPatchRoot() { return generatedRoot == null ? null : (String) invoke(generatedRoot, String.class); }
        public boolean recordObservation(Map<String, ?> value) { return observation != null && (Boolean) invoke(observation, value, Boolean.class); }
        public String expandOperationJson(String value) { try { Method method = receiver.getClass().getMethod("expandOperationJson", String.class); return (String) invoke(method, value, String.class); } catch (ReflectiveOperationException exception) { throw new IllegalStateException("Macro expansion is unavailable.", exception); } }

        private static Method method(Class<?> type, String name, Class<?> result) throws ReflectiveOperationException {
            Method method = type.getMethod(name, long.class);
            if (method.getReturnType() != result) throw new NoSuchMethodException(name);
            return method;
        }
        private static Method contributionMethod(Class<?> type, String name) throws ReflectiveOperationException {
            if (name.equals("replayContributions")) return type.getMethod(name, long.class, List.class);
            return type.getMethod(name, Map.class);
        }
        private static Method optionalContributionMethod(Class<?> type, String name) {
            try { return contributionMethod(type, name); } catch (ReflectiveOperationException ignored) { return null; }
        }
        private static Method noArgMethod(Class<?> type, String name, Class<?> result) throws ReflectiveOperationException {
            Method method = type.getMethod(name); if (method.getReturnType() != result) throw new NoSuchMethodException(name); return method;
        }
        private static Method optionalNoArgMethod(Class<?> type, String name, Class<?> result) {
            try { return noArgMethod(type, name, result); } catch (ReflectiveOperationException ignored) { return null; }
        }

        private Object invoke(Method method, long value, Class<?> expected) {
            try {
                Object result = method.invoke(receiver, value);
                if (expected == Void.class || expected.isInstance(result)) return result;
                throw new IllegalStateException("Invalid bridge return: " + method.getName());
            } catch (ReflectiveOperationException exception) { throw new IllegalStateException("Bridge lifecycle failure: " + method.getName(), exception); }
        }
        private Object invoke(Method method, Object value, Class<?> expected) {
            try { Object result = method.invoke(receiver, value); if (expected == Void.class || expected.isInstance(result)) return result; throw new IllegalStateException("Invalid bridge return: " + method.getName()); }
            catch (ReflectiveOperationException exception) { throw new IllegalStateException("Bridge lifecycle failure: " + method.getName(), exception); }
        }
        private Object invoke(Method method, long epoch, Object value, Class<?> expected) {
            try { Object result = method.invoke(receiver, epoch, value); if (expected == Void.class || expected.isInstance(result)) return result; throw new IllegalStateException("Invalid bridge return: " + method.getName()); }
            catch (ReflectiveOperationException exception) { throw new IllegalStateException("Bridge lifecycle failure: " + method.getName(), exception); }
        }
        private Object invoke(Method method, Class<?> expected) {
            try { Object result = method.invoke(receiver); if (expected == Void.class || expected.isInstance(result)) return result; throw new IllegalStateException("Invalid bridge return: " + method.getName()); }
            catch (ReflectiveOperationException exception) { throw new IllegalStateException("Bridge lifecycle failure: " + method.getName(), exception); }
        }
    }
}
