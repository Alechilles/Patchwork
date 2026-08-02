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
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
                assertFalse(manifest.get("IncludesAssetPack").getAsBoolean());
            }
            assertNotNull(standalone.getJarEntry("com/alechilles/patchwork/standalone/PatchworkPlugin.class"));
            assertNotNull(standalone.getJarEntry("com/alechilles/patchwork/embedded/StandalonePatchworkBootstrap.class"));
            assertNotNull(standalone.getJarEntry("META-INF/maven/com.alechilles/patchwork-runtime/pom.properties"));
            assertNotNull(standalone.getJarEntry("icon-256.png"),
                    "The standalone artifact must publish its mod icon beside the manifest.");
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
        try (var files = Files.list(directory)) {
            List<Path> matches = files.filter(path -> path.getFileName().toString().startsWith(prefix + "-")
                            && path.getFileName().toString().endsWith(".jar")
                            && !path.getFileName().toString().startsWith("original-")
                            && !path.getFileName().toString().endsWith("-shaded.jar"))
                    .toList();
            assertEquals(1, matches.size(), "Expected one final " + prefix + " artifact in " + directory);
            return matches.getFirst();
        }
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
