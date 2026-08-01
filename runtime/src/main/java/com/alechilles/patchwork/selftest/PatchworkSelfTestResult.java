package com.alechilles.patchwork.selftest;

import java.nio.file.Path;
import java.util.List;

/** Structured, sanitized result of one isolated self-test run. */
public record PatchworkSelfTestResult(Path runDirectory, boolean started, boolean cleanupAttempted, boolean cleanupSucceeded,
                                      List<String> outcomes, String diagnostic, boolean completed, boolean cancelled,
                                      GenerationOutcome generationOutcome, PatchworkSelfTestReloadHandle.ReloadOutcome reloadOutcome,
                                      List<String> generatedTargets, List<CaseOutcome> caseOutcomes) {
    public PatchworkSelfTestResult {
        outcomes = List.copyOf(outcomes); diagnostic = diagnostic == null ? "" : diagnostic;
        generationOutcome = generationOutcome == null ? GenerationOutcome.FAILED : generationOutcome;
        reloadOutcome = reloadOutcome == null ? PatchworkSelfTestReloadHandle.ReloadOutcome.RESTART_REQUIRED : reloadOutcome;
        generatedTargets = List.copyOf(generatedTargets); caseOutcomes = List.copyOf(caseOutcomes);
    }
    /** Compatibility constructor for existing administration seams. */
    public PatchworkSelfTestResult(Path runDirectory, boolean started, boolean cleanupAttempted, boolean cleanupSucceeded, List<String> outcomes, String diagnostic) {
        this(runDirectory, started, cleanupAttempted, cleanupSucceeded, outcomes, diagnostic, started, false,
                started ? GenerationOutcome.GENERATED : GenerationOutcome.FAILED, PatchworkSelfTestReloadHandle.ReloadOutcome.RESTART_REQUIRED, List.of(), List.of());
    }
    public enum GenerationOutcome { GENERATED, FAILED, CANCELLED }
    /** One case plus every output check; values remain confined to the isolated test result. */
    public record CaseOutcome(String target, boolean generated, boolean passed, List<CheckOutcome> checks, String diagnostic) {
        public CaseOutcome { checks = List.copyOf(checks); diagnostic = diagnostic == null ? "" : diagnostic; }
    }
    public record CheckOutcome(String pointer, boolean passed) { }
}
