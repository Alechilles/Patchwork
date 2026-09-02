package com.alechilles.patchwork.telemetry;

import com.alechilles.beacon.consent.TelemetryConsentCapabilities;
import com.alechilles.beacon.project.TelemetryProjectDescriptor;
import com.alechilles.beacon.project.TelemetryProjectDiscovery;
import com.alechilles.beacon.project.TelemetryProjectRegistration;
import com.alechilles.patchwork.PatchworkVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchworkTelemetryDescriptorTest {

    @TempDir
    Path tempDir;

    @Test
    void consentOffersOnlyCrashAndStats() throws IOException {
        TelemetryProjectDescriptor descriptor;
        try (InputStream stream = PatchworkTelemetry.class.getClassLoader()
                .getResourceAsStream("META-INF/beacon/projects/patchwork.json")) {
            assertNotNull(stream);
            descriptor = TelemetryProjectDescriptor.fromJson(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                    null
            );
        }
        TelemetryProjectRegistration registration = new TelemetryProjectRegistration(
                descriptor,
                "Alechilles:Patchwork",
                PatchworkVersion.current(),
                null
        );

        assertEquals(
                List.of("crash", "stats"),
                TelemetryConsentCapabilities.supportedCategoryNames(registration)
        );
        assertTrue(descriptor.capture().uncaughtExceptions());
    }

    @Test
    void shadedDescriptorSupportsPassiveDiscovery() throws Exception {
        Path hostJar = tempDir.resolve("Alec's Tamework.jar");
        try (InputStream descriptor = PatchworkTelemetry.class.getClassLoader()
                .getResourceAsStream("META-INF/beacon/projects/patchwork.json");
             ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(hostJar))) {
            assertNotNull(descriptor);
            writeEntry(archive, "manifest.json", """
                    {
                      "Group": "Alechilles",
                      "Name": "Alec's Tamework!",
                      "Version": "3.1.9",
                      "Main": "com.alechilles.alecstamework.Tamework"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            writeEntry(
                    archive,
                    "META-INF/beacon/projects/patchwork.json",
                    descriptor.readAllBytes()
            );
        }

        TelemetryProjectDiscovery.DiscoveryResult result = new TelemetryProjectDiscovery(null)
                .discover(tempDir);
        assertTrue(result.skippedRegistrationWarnings().isEmpty());
        assertEquals(1, result.projects().size());
        assertEquals("patchwork", result.projects().getFirst().projectId());
        assertEquals(PatchworkVersion.current(), result.projects().getFirst().pluginVersion());
    }

    private static void writeEntry(ZipOutputStream archive, String path, byte[] bytes) throws IOException {
        archive.putNextEntry(new ZipEntry(path));
        archive.write(bytes);
        archive.closeEntry();
    }
}
