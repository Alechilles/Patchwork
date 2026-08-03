package com.alechilles.patchwork.embedded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.patchwork.generation.PatchStatusSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class HytaleEarlyLoadCompositionTest {
    @Test
    void recoverableTargetRejectionIsReportedWithoutFailingAssetLoad() {
        PatchStatusSnapshot status = new PatchStatusSnapshot(
                List.of(),
                Map.of("Server/Tamework/Companion/AHCompBeast.json", "Target was not found."),
                List.of());
        List<String> warnings = new ArrayList<>();

        HytaleEarlyLoadComposition.reportRecoverableDiagnostics(status, warnings::add);

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("Server/Tamework/Companion/AHCompBeast.json"));
        assertTrue(warnings.getFirst().contains("Target was not found."));
    }
}
