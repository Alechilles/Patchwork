package com.alechilles.patchwork.telemetry;

import com.alechilles.alecstelemetry.consent.TelemetryConsentCapabilities;
import com.alechilles.alecstelemetry.project.TelemetryProjectDescriptor;
import com.alechilles.alecstelemetry.project.TelemetryProjectRegistration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchworkTelemetryDescriptorTest {

    @Test
    void consentOffersOnlyCrashAndStats() throws IOException {
        TelemetryProjectDescriptor descriptor;
        try (InputStream stream = PatchworkTelemetry.class.getClassLoader()
                .getResourceAsStream("META-INF/alecs-telemetry/projects/patchwork.json")) {
            assertNotNull(stream);
            descriptor = TelemetryProjectDescriptor.fromJson(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                    null
            );
        }
        TelemetryProjectRegistration registration = new TelemetryProjectRegistration(
                descriptor,
                "Alechilles:Patchwork",
                "1.3.1",
                null
        );

        assertEquals(
                List.of("crash", "stats"),
                TelemetryConsentCapabilities.supportedCategoryNames(registration)
        );
        assertTrue(descriptor.capture().uncaughtExceptions());
    }
}
