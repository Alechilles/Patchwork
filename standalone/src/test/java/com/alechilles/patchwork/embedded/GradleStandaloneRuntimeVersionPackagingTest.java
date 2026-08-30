package com.alechilles.patchwork.embedded;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the Gradle distributable supplies the version required during bootstrap. */
@EnabledIfSystemProperty(named = "patchwork.standaloneJar", matches = ".+")
class GradleStandaloneRuntimeVersionPackagingTest {
    @Test
    void distributablePrefersItsOwnVersionOverForeignRuntimeMetadata() throws Exception {
        try (JarFile jar = new JarFile(Path.of(System.getProperty("patchwork.standaloneJar")).toFile())) {
            String packageVersion = jar.getManifest().getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);

            assertEquals(System.getProperty("patchwork.version"),
                    StandalonePatchworkBootstrap.resolveRuntimeVersion("9.9.9", packageVersion));
        }
    }

    @Test
    void distributablePublishesEmbeddedTelemetryConsentUi() throws Exception {
        try (JarFile jar = new JarFile(Path.of(System.getProperty("patchwork.standaloneJar")).toFile())) {
            var manifestEntry = jar.getJarEntry("manifest.json");
            assertNotNull(manifestEntry);
            try (var input = new InputStreamReader(jar.getInputStream(manifestEntry))) {
                var manifest = JsonParser.parseReader(input).getAsJsonObject();
                assertTrue(manifest.get("IncludesAssetPack").getAsBoolean(),
                        "Patchwork must publish the embedded Telemetry UI to clients.");
            }
            assertNotNull(jar.getJarEntry("Common/UI/Custom/TelemetryConsentPage.ui"));
        }
    }

    @Test
    void distributableEmbedsSupportedTelemetryRuntime() throws Exception {
        try (JarFile jar = new JarFile(Path.of(System.getProperty("patchwork.standaloneJar")).toFile())) {
            var metadata = jar.getJarEntry(
                    "META-INF/maven/com.alechilles/alecstelemetry-runtime/pom.properties");
            assertNotNull(metadata, "The distributable must identify its embedded Telemetry runtime.");
            var properties = new Properties();
            try (var input = jar.getInputStream(metadata)) {
                properties.load(input);
            }
            assertEquals("1.2.3", properties.getProperty("version"));
        }
    }
}
