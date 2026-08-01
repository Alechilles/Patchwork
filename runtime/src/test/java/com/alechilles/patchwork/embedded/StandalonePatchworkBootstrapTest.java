package com.alechilles.patchwork.embedded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.patchwork.coordinator.PatchworkCoordinatorRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StandalonePatchworkBootstrapTest {
    @Test
    void standaloneWinsAnEqualVersionTieAndClosesOnlyItsOwnRegistration() {
        Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try {
            EmbeddedPatchworkService embedded = EmbeddedPatchworkBootstrap.createEmbeddedService(
                    "embedded", "1.0.0", Path.of("embedded.jar"), Path.of("build/embedded/data"), () -> { });
            StandalonePatchworkService standalone = StandalonePatchworkBootstrap.createStandaloneService(
                    "standalone", "1.0.0", Path.of("standalone.jar"), Path.of("build/standalone/data"), () -> { });

            embedded.start();
            standalone.start();

            assertEquals("standalone", PatchworkCoordinatorRegistry.activeProviderId());
            assertEquals("STANDALONE", ((java.util.List<java.util.Map<String, ?>>) PatchworkCoordinatorRegistry.adminSnapshot().get("candidates"))
                    .stream().filter(row -> Boolean.TRUE.equals(row.get("active"))).findFirst().orElseThrow().get("origin"));
            standalone.close();
            assertEquals("embedded", PatchworkCoordinatorRegistry.activeProviderId());
            embedded.close();
            assertNull(PatchworkCoordinatorRegistry.activeProviderId());
        } finally {
            if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
            else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original);
        }
    }

    @Test
    void newerEmbeddedRuntimeBeatsAnOlderStandaloneRuntime() {
        Object original = System.getProperties().get(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
        try {
            StandalonePatchworkService standalone = StandalonePatchworkBootstrap.createStandaloneService(
                    "standalone", "1.0.0", Path.of("standalone.jar"), Path.of("build/standalone/data"), () -> { });
            EmbeddedPatchworkService embedded = EmbeddedPatchworkBootstrap.createEmbeddedService(
                    "embedded", "2.0.0", Path.of("embedded.jar"), Path.of("build/embedded/data"), () -> { });

            standalone.start();
            embedded.start();

            assertEquals("embedded", PatchworkCoordinatorRegistry.activeProviderId());
            embedded.close();
            standalone.close();
        } finally {
            if (original == null) System.getProperties().remove(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY);
            else System.getProperties().put(PatchworkCoordinatorRegistry.REGISTRY_PROPERTY, original);
        }
    }
}
