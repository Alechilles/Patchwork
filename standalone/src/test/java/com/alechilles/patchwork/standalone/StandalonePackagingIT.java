package com.alechilles.patchwork.standalone;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the runtime and standalone artifacts retain their intended
 * packaging boundary.
 */
class StandalonePackagingIT {
    private static final Path PARENT_POM = Path.of("..", "pom.xml");
    private static final Path RUNTIME_POM = Path.of("..", "runtime", "pom.xml");
    private static final Path STANDALONE_POM = Path.of("pom.xml");

    @Test
    void packagesAHeadlessRuntimeAndAStandalonePluginArtifact() throws Exception {
        try (JarFile runtime = new JarFile(runtimeJar().toFile());
             JarFile standalone = new JarFile(standaloneJar().toFile())) {
            assertFalse(runtime.stream().anyMatch(entry -> entry.getName().equals("manifest.json")),
                    "The reusable runtime must not publish a Hytale plugin manifest.");

            var manifestEntry = standalone.getJarEntry("manifest.json");
            assertNotNull(manifestEntry, "The standalone artifact must publish a Hytale plugin manifest.");
            try (InputStream input = standalone.getInputStream(manifestEntry)) {
                JsonObject manifest = JsonParser.parseReader(new java.io.InputStreamReader(input)).getAsJsonObject();
                assertEquals("Alechilles", manifest.get("Group").getAsString());
                assertEquals("Patchwork", manifest.get("Name").getAsString());
                assertEquals("com.alechilles.patchwork.standalone.PatchworkPlugin", manifest.get("Main").getAsString());
                assertTrue(manifest.get("IncludesAssetPack").getAsBoolean());
            }
            assertNotNull(standalone.getJarEntry("com/alechilles/patchwork/standalone/PatchworkPlugin.class"));
            assertNotNull(standalone.getJarEntry("com/alechilles/patchwork/embedded/StandalonePatchworkBootstrap.class"));
            assertNotNull(standalone.getJarEntry("META-INF/maven/com.alechilles/patchwork-runtime/pom.properties"));
            assertNotNull(standalone.getJarEntry("icon-256.png"),
                    "The standalone artifact must publish its mod icon beside the manifest.");
            assertNotNull(standalone.getJarEntry("Server/Patchwork/Patches/AnimalHusbandry/AH_Saddle_Bear.json"));
            assertNotNull(standalone.getJarEntry("Server/Patchwork/Patches/HyDragon/RockDrake_Zone2_Cave_Volcanic_T2_Aggro.json"));
            assertNotNull(standalone.getJarEntry("Server/Patchwork/Patches/Tamework/Items/Tamework_Tool_Capture_Crate_Patch.json"));
            assertEquals(1, standalone.stream().filter(entry -> entry.getName().equals("manifest.json")).count());
            assertFalse(standalone.stream().anyMatch(entry -> entry.getName().matches("META-INF/.*\\.(SF|DSA|RSA)")));
        }

        assertEquals(0, shadePluginCount(PARENT_POM), "The parent must not configure inherited shading.");
        assertEquals(0, shadePluginCount(RUNTIME_POM), "The runtime must not configure shading.");
        assertEquals(1, shadePluginCount(STANDALONE_POM), "Only the standalone module may configure shading.");
    }

    private static Path runtimeJar() throws Exception { return artifact(Path.of("..", "runtime", "target"), "patchwork-runtime"); }
    private static Path standaloneJar() throws Exception { return artifact(Path.of("target"), "patchwork-standalone"); }

    private static Path artifact(Path directory, String prefix) throws Exception {
        Path artifact = directory.resolve(prefix + "-" + projectVersion() + ".jar");
        assertTrue(Files.isRegularFile(artifact), "Expected the current " + prefix + " artifact in " + directory);
        return artifact;
    }
    private static String projectVersion() throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(PARENT_POM.toFile())
                .getDocumentElement().getElementsByTagName("version").item(0).getTextContent();
    }

    private static int shadePluginCount(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        Document document = factory.newDocumentBuilder().parse(pom.toFile());
        NodeList artifactIds = document.getElementsByTagName("artifactId");
        int count = 0;
        for (int index = 0; index < artifactIds.getLength(); index++) {
            if ("maven-shade-plugin".equals(artifactIds.item(index).getTextContent())) {
                count++;
            }
        }
        return count;
    }
}
