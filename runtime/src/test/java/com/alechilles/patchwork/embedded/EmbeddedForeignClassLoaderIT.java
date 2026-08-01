package com.alechilles.patchwork.embedded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.google.gson.JsonArray;
import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the public embedded service API from genuinely copied, isolated runtime jars. */
final class EmbeddedForeignClassLoaderIT {
    @TempDir Path temporary;

    @Test void oldEmbeddedServiceForwardsToNewerElectedRuntimeAcrossCopiedJars() throws Exception {
        Path jar = Path.of(EmbeddedPatchworkBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path oldJar = Files.copy(jar, temporary.resolve("old-runtime.jar")); Path newJar = Files.copy(jar, temporary.resolve("new-runtime.jar"));
        Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try (URLClassLoader oldLoader = isolated(oldJar); URLClassLoader newLoader = isolated(newJar)) {
            Object oldService = service(oldLoader, "old", "1.0.0", temporary.resolve("old/data"));
            invoke(oldService, "start"); Object installed = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
            Object newService = service(newLoader, "new", "2.0.0", temporary.resolve("new/data")); invoke(newService, "start");
            assertSame(installed, System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY));
            assertEquals(temporary.resolve("new/Alechilles_Patchwork/GeneratedPatches").toAbsolutePath().normalize(), invoke(oldService, "generatedPatchRoot"));
            Object contribution = contribution(oldLoader);
            Object handle = oldLoader.loadClass("com.alechilles.patchwork.embedded.EmbeddedPatchworkService").getMethod("registerContribution", oldLoader.loadClass("com.alechilles.patchwork.embedded.PatchworkHostContribution")).invoke(oldService, contribution);
            assertEquals("[{\"Id\":\"expanded\",\"Op\":\"Replace\",\"Path\":\"/value\",\"Required\":true,\"Value\":2}]", PatchworkCoordinatorRegistry.expandOperationJson("{\"Id\":\"x\",\"Op\":\"Macro\",\"Macro\":\"old-macro\"}"));
            invoke(handle, "close");
            Object outcome = Enum.valueOf((Class) oldLoader.loadClass("com.alechilles.patchwork.embedded.PatchworkObservationOutcome"), "LOADED");
            Object observation = oldLoader.loadClass("com.alechilles.patchwork.embedded.PatchworkReloadObservation").getConstructor(long.class, String.class, String.class, String.class, oldLoader.loadClass("com.alechilles.patchwork.embedded.PatchworkObservationOutcome")).newInstance(2L, "adapter", "Server/Test.json", "hash", outcome);
            oldLoader.loadClass("com.alechilles.patchwork.embedded.EmbeddedPatchworkService").getMethod("recordObservation", oldLoader.loadClass("com.alechilles.patchwork.embedded.PatchworkReloadObservation")).invoke(oldService, observation);
            invoke(oldService, "close"); invoke(newService, "close");
            assertFalse(System.getProperties().containsKey("patchwork.embedded.it.failure"));
        } finally { if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY); else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original); }
    }

    private Object service(URLClassLoader loader, String id, String version, Path data) throws Exception {
        Class<?> bootstrap = loader.loadClass("com.alechilles.patchwork.embedded.EmbeddedPatchworkBootstrap");
        Method factory = bootstrap.getDeclaredMethod("createEmbeddedService", String.class, String.class, Path.class, Path.class, Runnable.class); factory.setAccessible(true);
        return factory.invoke(null, id, version, temporary.resolve(id + ".jar"), data, (Runnable) () -> { });
    }
    private static Object contribution(URLClassLoader loader) throws Exception {
        Class<?> contribution = loader.loadClass("com.alechilles.patchwork.embedded.PatchworkHostContribution");
        Class<?> macro = loader.loadClass("com.alechilles.patchwork.embedded.PatchworkMacroProvider");
        Object macroProvider = Proxy.newProxyInstance(loader, new Class<?>[] { macro }, (proxy, method, args) -> {
            if (method.getName().equals("macroId")) return "old-macro";
            if (method.getName().equals("expand")) { Object array = loader.loadClass("com.google.gson.JsonArray").getConstructor().newInstance(); Object object = loader.loadClass("com.google.gson.JsonParser").getMethod("parseString", String.class).invoke(null, "{\"Id\":\"expanded\",\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":2}"); array.getClass().getMethod("add", loader.loadClass("com.google.gson.JsonElement")).invoke(array, object); return array; }
            throw new UnsupportedOperationException(method.getName()); });
        return Proxy.newProxyInstance(loader, new Class<?>[] { contribution }, (proxy, method, args) -> switch (method.getName()) {
            case "hostPluginIdentifier" -> "foreign-host"; case "contributionVersion" -> "1"; case "macroProviders" -> List.of(macroProvider); case "targetAdapters" -> List.of(); default -> throw new UnsupportedOperationException(method.getName()); });
    }
    private static Object invoke(Object target, String method) throws Exception { return target.getClass().getInterfaces()[0].getMethod(method).invoke(target); }
    private static URLClassLoader isolated(Path jar) throws Exception { return new URLClassLoader(new java.net.URL[] { jar.toUri().toURL(), Path.of(JavaPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toUri().toURL(), Path.of(JsonArray.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toUri().toURL() }, ClassLoader.getPlatformClassLoader()); }
}
