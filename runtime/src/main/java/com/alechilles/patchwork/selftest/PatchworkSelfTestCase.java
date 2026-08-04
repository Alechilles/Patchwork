package com.alechilles.patchwork.selftest;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable source, patch, optional ModData, and expected-output fixture for one isolated run. */
public record PatchworkSelfTestCase(String sourceTargetPath, String sourceTargetJson, String patchDefinitionPath,
                                    String patchDefinitionJson, String registeredModId, Map<String, String> modDataDocuments,
                                    String expectedGeneratedTarget, Map<String, String> expectedPointers,
                                    Map<String, String> fixtureAssets) {
    public PatchworkSelfTestCase(String sourceTargetPath, String sourceTargetJson, String patchDefinitionPath,
                                 String patchDefinitionJson, String registeredModId, Map<String, String> modDataDocuments,
                                 String expectedGeneratedTarget, Map<String, String> expectedPointers) {
        this(sourceTargetPath, sourceTargetJson, patchDefinitionPath, patchDefinitionJson, registeredModId,
                modDataDocuments, expectedGeneratedTarget, expectedPointers, Map.of());
    }
    public PatchworkSelfTestCase {
        sourceTargetPath = path(sourceTargetPath, "source target");
        patchDefinitionPath = path(patchDefinitionPath, "patch definition");
        expectedGeneratedTarget = path(expectedGeneratedTarget, "generated target");
        sourceTargetJson = Objects.requireNonNullElse(sourceTargetJson, "");
        patchDefinitionJson = Objects.requireNonNullElse(patchDefinitionJson, "");
        if ((registeredModId == null || registeredModId.isBlank()) && modDataDocuments != null && !modDataDocuments.isEmpty()) throw new IllegalArgumentException("Fixture ModData requires a registered mod ID.");
        registeredModId = registeredModId == null || registeredModId.isBlank() ? null : modId(registeredModId);
        Map<String, String> docs = new LinkedHashMap<>();
        if (modDataDocuments != null) modDataDocuments.forEach((key, value) -> docs.put(path(key, "ModData document"), Objects.requireNonNullElse(value, "")));
        modDataDocuments = Map.copyOf(docs);
        Map<String, String> pointers = new LinkedHashMap<>();
        if (expectedPointers != null) expectedPointers.forEach((pointer, expected) -> {
            if (pointer == null || !pointer.startsWith("/") || pointer.contains("\\") || pointer.contains("..")) throw new IllegalArgumentException("Unsafe expected JSON pointer.");
            pointers.put(pointer, Objects.requireNonNullElse(expected, "null"));
        });
        expectedPointers = Map.copyOf(pointers);
        Map<String, String> assets = new LinkedHashMap<>();
        if (fixtureAssets != null) fixtureAssets.forEach((key, value) -> assets.put(path(key, "fixture asset"), Objects.requireNonNullElse(value, "")));
        fixtureAssets = Map.copyOf(assets);
    }

    /** Applies the generated run ID without introducing a macro or runtime configuration surface. */
    PatchworkSelfTestCase forRun(String runId) {
        return new PatchworkSelfTestCase(sourceTargetPath, substitute(sourceTargetJson, runId), patchDefinitionPath,
                substitute(patchDefinitionJson, runId), registeredModId, substitute(modDataDocuments, runId),
                expectedGeneratedTarget, substitute(expectedPointers, runId), substitute(fixtureAssets, runId));
    }

    private static Map<String, String> substitute(Map<String, String> values, String runId) {
        Map<String, String> copy = new LinkedHashMap<>(); values.forEach((key, value) -> copy.put(key, substitute(value, runId))); return copy;
    }
    private static String substitute(String value, String runId) { return value == null ? null : value.replace("${runId}", runId); }
    private static String path(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0 || value.startsWith("/") || value.matches("^[A-Za-z]:.*") || Path.of(value).isAbsolute()) throw new IllegalArgumentException("Unsafe " + name + " path.");
        for (String part : value.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Unsafe " + name + " path.");
        return value;
    }
    private static String modId(String value) {
        String id = value.trim();
        if (id.contains("/") || id.contains("\\") || id.equals(".") || id.equals("..") || id.contains("..")) throw new IllegalArgumentException("Unsafe registered mod ID.");
        return id;
    }
}
