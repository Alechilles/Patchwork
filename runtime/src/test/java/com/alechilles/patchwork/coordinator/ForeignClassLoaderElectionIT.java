package com.alechilles.patchwork.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies two isolated runtime jars elect through a JDK-only reflective surface. */
final class ForeignClassLoaderElectionIT {
    @TempDir Path temporary;

    @Test
    void electsOneWinnerThroughTwoCopiedIsolatedRuntimeJars() throws Exception {
        // Catches a mutation that stores registry state per loader or exposes Patchwork types over reflection.
        Path jar = runtimeJar();
        Path firstCopy = Files.copy(jar, temporary.resolve("first-runtime.jar"));
        Path secondCopy = Files.copy(jar, temporary.resolve("second-runtime.jar"));
        Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try (URLClassLoader first = new URLClassLoader(new java.net.URL[]{firstCopy.toUri().toURL(), testClasses().toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             URLClassLoader second = new URLClassLoader(new java.net.URL[]{secondCopy.toUri().toURL(), testClasses().toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> firstRegistry = first.loadClass("com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry");
            Class<?> secondRegistry = second.loadClass("com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry");
            System.setProperty("patchwork.it.events", "");
            var register = firstRegistry.getMethod("register", Map.class);
            Object firstBridge = first.loadClass(BridgeOne.class.getName()).getConstructor().newInstance();
            Object secondBridge = second.loadClass(BridgeTwo.class.getName()).getConstructor().newInstance();
            String oldToken = (String) register.invoke(null, descriptor("first", "1.0.0", firstBridge));
            String newToken = (String) secondRegistry.getMethod("register", Map.class).invoke(null, descriptor("first", "2.0.0", secondBridge, oldToken));
            assertEquals("first", firstRegistry.getMethod("activeProviderId").invoke(null));
            assertFalse((Boolean) secondRegistry.getMethod("unregister", String.class).invoke(null, oldToken));
            assertFalse((Boolean) firstRegistry.getMethod("publish", String.class).invoke(null, oldToken));
            assertTrue((Boolean) secondRegistry.getMethod("publish", String.class).invoke(null, newToken));
            assertEquals("one:activate:1,one:start:1,one:fence:1,one:drain:1,one:deactivate:1,two:activate:2,two:start:2,two:publish:2,", System.getProperty("patchwork.it.events"));
            for (var method : firstRegistry.getDeclaredMethods()) if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                for (Class<?> parameter : method.getParameterTypes()) assertEquals(true, allowed(parameter));
                assertEquals(true, allowed(method.getReturnType()));
            }
        } finally {
            if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
            else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original);
        }
    }

    @Test
    void concurrentForeignFirstRegistrationInstallsOneStableRegistryObject() throws Exception {
        // Catches two isolated loaders racing to install distinct global registry objects or observing different winners.
        Path jar = runtimeJar();
        Path firstCopy = Files.copy(jar, temporary.resolve("concurrent-first.jar"));
        Path secondCopy = Files.copy(jar, temporary.resolve("concurrent-second.jar"));
        Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try (URLClassLoader first = isolated(firstCopy); URLClassLoader second = isolated(secondCopy)) {
            Class<?> firstRegistry = first.loadClass("com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry");
            Class<?> secondRegistry = second.loadClass("com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry");
            CyclicBarrier barrier = new CyclicBarrier(2);
            FutureTask<Registration> firstTask = registerAtBarrier(firstRegistry, descriptor("first", "1.0.0", first.loadClass(BridgeOne.class.getName()).getConstructor().newInstance()), barrier);
            FutureTask<Registration> secondTask = registerAtBarrier(secondRegistry, descriptor("second", "2.0.0", second.loadClass(BridgeTwo.class.getName()).getConstructor().newInstance()), barrier);
            Thread firstThread = new Thread(firstTask, "foreign-first"); Thread secondThread = new Thread(secondTask, "foreign-second");
            firstThread.start(); secondThread.start(); Registration firstRegistration = firstTask.get(); Registration secondRegistration = secondTask.get(); firstThread.join(); secondThread.join();
            Object installed = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
            assertTrue(installed != null); assertEquals("second", firstRegistry.getMethod("activeProviderId").invoke(null));
            assertEquals("second", secondRegistry.getMethod("activeProviderId").invoke(null));
            assertTrue(installed == firstRegistration.property()); assertTrue(installed == secondRegistration.property());
        } finally {
            if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
            else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original);
        }
    }

    @Test
    void foreignInactiveLookupReturnsJavaNull() throws Exception {
        // Catches reflective foreign invocation stringifying a missing owner as the literal "null".
        Path jar = runtimeJar();
        Path copy = Files.copy(jar, temporary.resolve("inactive-runtime.jar")); Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try (URLClassLoader loader = isolated(copy)) {
            Class<?> registry = loader.loadClass("com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry");
            assertEquals(null, registry.getMethod("activeProviderId").invoke(null));
        } finally { if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original); }
    }

    @Test
    void foreignPublishExceptionReturnsFalseAndReleasesTheGuard() throws Exception {
        // Catches reflective publish leaking bridge exceptions or permanently retaining the publication guard.
        Path jar = runtimeJar();
        Path copy = Files.copy(jar, temporary.resolve("publish-runtime.jar")); Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try (URLClassLoader loader = isolated(copy)) {
            Class<?> registry = loader.loadClass("com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry");
            Object bridge = loader.loadClass(ThrowingPublishBridge.class.getName()).getConstructor().newInstance();
            String token = (String) registry.getMethod("register", Map.class).invoke(null, descriptor("publish", "1.0.0", bridge));
            assertFalse((Boolean) registry.getMethod("publish", String.class).invoke(null, token));
            assertTrue((Boolean) registry.getMethod("publish", String.class).invoke(null, token));
        } finally { if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original); }
    }

    @Test
    void foreignRegistrationRetainsTheExactRecoveryTokenUntilItsCleanupRetryElectsTheFallback() throws Exception {
        Path jar = runtimeJar();
        Path firstCopy = Files.copy(jar, temporary.resolve("recovery-first.jar")); Path secondCopy = Files.copy(jar, temporary.resolve("recovery-second.jar"));
        Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try (URLClassLoader first = isolated(firstCopy); URLClassLoader second = isolated(secondCopy)) {
            Class<?> firstRegistry = first.loadClass("com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry");
            Class<?> secondRegistry = second.loadClass("com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry");
            String fallback = (String) firstRegistry.getMethod("register", Map.class).invoke(null, descriptor("fallback", "1.0.0", first.loadClass(BridgeOne.class.getName()).getConstructor().newInstance()));
            String recovery = (String) secondRegistry.getMethod("register", Map.class).invoke(null, descriptor("unsafe", "2.0.0", second.loadClass(ActivationAndCleanupFailureBridge.class.getName()).getConstructor().newInstance()));

            assertEquals("RECOVERY_REQUIRED", firstRegistry.getMethod("registrationState", String.class).invoke(null, recovery));
            assertFalse((Boolean) firstRegistry.getMethod("publish", String.class).invoke(null, fallback));
            assertTrue((Boolean) secondRegistry.getMethod("unregister", String.class).invoke(null, recovery));
            assertEquals("MISSING", firstRegistry.getMethod("registrationState", String.class).invoke(null, recovery));
            assertEquals("fallback", firstRegistry.getMethod("activeProviderId").invoke(null));
        } finally { if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original); }
    }

    @Test
    void abiOneHandleFailsItsStartButRetainsTheRecoveryTokenForItsLaterExactClose() throws Exception {
        // An ABI-1 handle assigns register's return before it calls publish and ignores publish's boolean result.
        Path jar = runtimeJar();
        Path firstCopy = Files.copy(jar, temporary.resolve("legacy-recovery-first.jar")); Path secondCopy = Files.copy(jar, temporary.resolve("legacy-recovery-second.jar"));
        Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try (URLClassLoader first = isolated(firstCopy); URLClassLoader second = isolated(secondCopy)) {
            Class<?> firstRegistry = first.loadClass("com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry");
            Class<?> secondRegistry = second.loadClass("com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry");
            String fallback = (String) firstRegistry.getMethod("register", Map.class).invoke(null, descriptor("fallback", "1.0.0", first.loadClass(BridgeOne.class.getName()).getConstructor().newInstance()));
            String retained = (String) secondRegistry.getMethod("register", Map.class).invoke(null, descriptor("unsafe", "2.0.0", second.loadClass(ActivationAndCleanupFailureBridge.class.getName()).getConstructor().newInstance()));

            java.lang.reflect.InvocationTargetException startFailure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> secondRegistry.getMethod("publish", String.class).invoke(null, retained));
            assertTrue(startFailure.getCause() instanceof IllegalStateException);
            assertEquals("RECOVERY_REQUIRED", firstRegistry.getMethod("registrationState", String.class).invoke(null, retained));
            assertTrue((Boolean) secondRegistry.getMethod("unregister", String.class).invoke(null, retained));
            assertEquals("fallback", firstRegistry.getMethod("activeProviderId").invoke(null));
            assertTrue((Boolean) firstRegistry.getMethod("publish", String.class).invoke(null, fallback));
        } finally { if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original); }
    }

    private static Map<String, Object> descriptor(String provider, String version, Object bridge) {
        return Map.of("providerId", provider, "origin", "STANDALONE", "runtimeVersion", version, "coordinatorAbi", 1,
                "providerPluginId", provider + ".plugin", "providerPluginVersion", "1", "sourceJarPath", Path.of("mods", provider + ".jar"),
                "providerDataRoot", Path.of("mods", provider), "bridge", bridge);
    }
    private static Map<String, Object> descriptor(String provider, String version, Object bridge, String replacementToken) {
        var values = new java.util.HashMap<>(descriptor(provider, version, bridge)); values.put("replacementToken", replacementToken); return values;
    }

    private static URLClassLoader isolated(Path jar) throws Exception { return new URLClassLoader(new java.net.URL[]{jar.toUri().toURL(), testClasses().toUri().toURL()}, ClassLoader.getPlatformClassLoader()); }
    private static FutureTask<Registration> registerAtBarrier(Class<?> registry, Map<String, Object> descriptor, CyclicBarrier barrier) {
        return new FutureTask<>(() -> { barrier.await(); String token = (String) registry.getMethod("register", Map.class).invoke(null, descriptor); return new Registration(token, System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY)); });
    }
    private record Registration(String token, Object property) { }

    private static boolean allowed(Class<?> type) { return type == String.class || type == Map.class || type == java.util.List.class || type == Path.class || type == byte[].class || type == java.util.concurrent.CompletionStage.class || type.isPrimitive() || Number.class.isAssignableFrom(type) || type == Boolean.class || type == Character.class || type == Void.TYPE; }
    private static Path runtimeJar() throws Exception {
        Path location = Path.of(PatchworkCoordinatorRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return Files.isRegularFile(location) ? location : location.getParent().resolve("patchwork-runtime-1.1.0.jar");
    }
    private static Path testClasses() { try { return Path.of(ForeignClassLoaderElectionIT.class.getProtectionDomain().getCodeSource().getLocation().toURI()); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    public static final class BridgeOne { public static void fence(long epoch) { event("one:fence", epoch); } public static void stopAcceptingAndDrain(long epoch) { event("one:drain", epoch); } public static void deactivate(long epoch) { event("one:deactivate", epoch); } public static void activate(long epoch) { event("one:activate", epoch); } public static void start(long epoch) { event("one:start", epoch); } public static boolean publish(long epoch) { event("one:publish", epoch); return true; } }
    public static final class BridgeTwo { public static void fence(long epoch) { event("two:fence", epoch); } public static void stopAcceptingAndDrain(long epoch) { event("two:drain", epoch); } public static void deactivate(long epoch) { event("two:deactivate", epoch); } public static void activate(long epoch) { event("two:activate", epoch); } public static void start(long epoch) { event("two:start", epoch); } public static boolean publish(long epoch) { event("two:publish", epoch); return true; } }
    public static final class ThrowingPublishBridge { private static int calls; public static void fence(long epoch) { } public static void stopAcceptingAndDrain(long epoch) { } public static void deactivate(long epoch) { } public static void activate(long epoch) { } public static void start(long epoch) { } public static boolean publish(long epoch) { if (calls++ == 0) throw new IllegalStateException("publish"); return true; } }
    public static final class ActivationAndCleanupFailureBridge {
        private static boolean cleanupFails = true;
        public static void fence(long epoch) { }
        public static void stopAcceptingAndDrain(long epoch) { }
        public static void deactivate(long epoch) { if (cleanupFails) { cleanupFails = false; throw new IllegalStateException("cleanup"); } }
        public static void activate(long epoch) { throw new IllegalStateException("activation"); }
        public static void start(long epoch) { }
        public static boolean publish(long epoch) { return false; }
    }
    private static void event(String event, long epoch) { System.setProperty("patchwork.it.events", System.getProperty("patchwork.it.events") + event + ':' + epoch + ','); }
}
