package com.alechilles.patchwork.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies generated publication ordering uses the same unsigned UTF-8 rule as expansion. */
final class GeneratedPackManifestTest {
    @Test
    void ordersNonAsciiTargetsByUnsignedUtf8Bytes() {
        String privateUse = new String(Character.toChars(0xE000));
        String supplementary = new String(Character.toChars(0x10000));
        GeneratedPackManifest manifest = new GeneratedPackManifest(List.of(
                new GeneratedPackManifest.Entry("Server/" + supplementary + ".json", new byte[0]),
                new GeneratedPackManifest.Entry("Server/" + privateUse + ".json", new byte[0])));

        assertEquals(List.of("Server/" + privateUse + ".json", "Server/" + supplementary + ".json"),
                manifest.entries().stream().map(GeneratedPackManifest.Entry::target).toList());
    }
}
