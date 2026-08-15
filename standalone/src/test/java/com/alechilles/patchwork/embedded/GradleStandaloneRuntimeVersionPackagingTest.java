package com.alechilles.patchwork.embedded;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies that the Gradle distributable supplies the version required during bootstrap. */
class GradleStandaloneRuntimeVersionPackagingTest {
    @Test
    void distributablePrefersItsOwnVersionOverForeignRuntimeMetadata() throws Exception {
        try (JarFile jar = new JarFile(Path.of(System.getProperty("patchwork.standaloneJar")).toFile())) {
            String packageVersion = jar.getManifest().getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);

            assertEquals(System.getProperty("patchwork.version"),
                    StandalonePatchworkBootstrap.resolveRuntimeVersion("9.9.9", packageVersion));
        }
    }
}
