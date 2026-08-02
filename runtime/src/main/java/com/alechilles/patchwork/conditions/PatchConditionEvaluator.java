package com.alechilles.patchwork.conditions;

import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.format.JsonMatcher;
import com.alechilles.patchwork.format.JsonPointer;
import com.alechilles.patchwork.format.PatchFormat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.math.BigInteger;

/** Evaluates immutable patch conditions against one target and a per-generation source resolver. */
public final class PatchConditionEvaluator {
    /** Evaluates one condition, retaining a safe diagnostic for non-matches and failures. */
    public Evaluation evaluate(PatchCondition condition, EvaluationContext context) { return evaluateNode(condition, context); }
    private Evaluation evaluateNode(PatchCondition c, EvaluationContext x) {
        if (c instanceof PatchCondition.Always) return matched();
        if (c instanceof PatchCondition.ModInstalled v) return v.modId().equals("") ? no("Invalid mod ID.") : x.installedIds().contains(v.modId()) ? matched() : no("Required mod is not installed: " + v.modId());
        if (c instanceof PatchCondition.AssetExists v) return asset(v.path(), true, x);
        if (c instanceof PatchCondition.AssetMissing v) return asset(v.path(), false, x);
        if (c instanceof PatchCondition.TargetExists) return x.targetBytes() != null ? matched() : no("Target is missing: " + x.targetPath());
        if (c instanceof PatchCondition.TargetProvidedBy v) return v.sourcePackId().equals(x.targetSourcePackId())
                ? matched() : no("Target is not provided by: " + v.sourcePackId());
        if (c instanceof PatchCondition.ModVersion v) return version(x.versions().get(v.modId()), v.matcher()) ? matched() : no("Installed mod version does not match: " + v.modId());
        if (c instanceof PatchCondition.ServerVersion v) return version(x.serverVersion(), v.matcher()) ? matched() : no("Server version does not match.");
        if (c instanceof PatchCondition.JsonPathExists v) return json(v.source(), v.path(), null, false, v.formatVersion(), x);
        if (c instanceof PatchCondition.JsonPathEquals v) return json(v.source(), v.path(), v.expected(), true, v.formatVersion(), x);
        if (c instanceof PatchCondition.All v) { for (PatchCondition child : v.children()) { Evaluation e = evaluateNode(child, x); if (e.status() != Status.MATCHED) return e; } return matched(); }
        if (c instanceof PatchCondition.Any v) { Evaluation last = no("No Any condition matched."); for (PatchCondition child : v.children()) { Evaluation e = evaluateNode(child, x); if (e.status() == Status.MATCHED) return e; if (e.status() == Status.FAILED) return e; last = e; } return last; }
        return invert(evaluateNode(((PatchCondition.Not) c).child(), x));
    }
    private Evaluation asset(String path, boolean exists, EvaluationContext x) { var result = x.resolver().assetResolution(x.sources(), path); if (result.status() == com.alechilles.patchwork.discovery.PatchTargetResolver.Status.FAILED) return failed(result.diagnostic()); boolean present = result.status() == com.alechilles.patchwork.discovery.PatchTargetResolver.Status.FOUND; return present == exists ? matched() : no(exists ? "Required asset is missing: " + path : "Asset is present: " + path); }
    private Evaluation json(ConditionSource source, String pointer, JsonElement expected, boolean equals,
                            int formatVersion, EvaluationContext x) {
        ConditionSourceResolver.Result result = x.resolver().resolve(source, x.targetPath(), x.targetBytes(), x.sources());
        if (result.status() == ConditionSourceResolver.ResultStatus.FAILED) return failed(result.diagnostic());
        if (result.status() == ConditionSourceResolver.ResultStatus.MISSING) return no(result.diagnostic());
        if (!PatchFormat.isVersion2(formatVersion)) {
            Pointer value = legacyPointer(result.document(), pointer);
            if (!value.present()) return no("JSON pointer did not resolve.");
            if (!equals) return matched();
            return value.value().equals(expected) ? matched() : no("JSON value did not match.");
        }
        final List<String> tokens;
        try {
            tokens = JsonPointer.tokens(pointer, formatVersion, false);
        } catch (IllegalArgumentException failure) {
            return failed("JSON pointer is invalid.");
        }
        Pointer value;
        try {
            value = pointer(result.document(), tokens, formatVersion);
        } catch (IllegalArgumentException failure) {
            return failed("JSON pointer is invalid.");
        }
        if (!value.present()) return no("JSON pointer did not resolve.");
        if (!equals) return matched();
        if (PatchFormat.isVersion2(formatVersion)) {
            JsonObject exact = new JsonObject();
            exact.add("$Equals", expected.deepCopy());
            return JsonMatcher.matches(value.value(), exact, formatVersion)
                    ? matched() : no("JSON value did not match.");
        }
        return value.value().equals(expected) ? matched() : no("JSON value did not match.");
    }

    private static Pointer legacyPointer(JsonElement doc, String path) {
        if (path.isEmpty()) return new Pointer(true, doc);
        if (!path.startsWith("/")) return new Pointer(false, null);
        JsonElement value = doc;
        for (String raw : path.substring(1).split("/", -1)) {
            String part = raw.replace("~1", "/").replace("~0", "~");
            if (value instanceof JsonObject object) {
                if (!object.has(part)) return new Pointer(false, null);
                value = object.get(part);
            } else if (value instanceof JsonArray array && part.matches("0|[1-9]\\d*")) {
                final int index;
                try {
                    index = Integer.parseInt(part);
                } catch (NumberFormatException failure) {
                    return new Pointer(false, null);
                }
                if (index >= array.size()) return new Pointer(false, null);
                value = array.get(index);
            } else {
                return new Pointer(false, null);
            }
        }
        return new Pointer(true, value);
    }

    private static Pointer pointer(JsonElement doc, List<String> tokens, int formatVersion) {
        JsonElement value = doc;
        for (String part : tokens) {
            if (value instanceof JsonObject object) {
                if (!object.has(part)) return new Pointer(false, null);
                value = object.get(part);
            } else if (value instanceof JsonArray array) {
                int index = JsonPointer.arrayIndex(part, array.size(), false, formatVersion);
                value = array.get(index);
            } else {
                return new Pointer(false, null);
            }
        }
        return new Pointer(true, value);
    }
    private static boolean version(String actual, PatchCondition.VersionMatcher m) { if (actual == null || !actual.matches("\\d+(\\.\\d+)*")) return false; return check(actual, m.equals(), 0) && check(actual, m.atLeast(), 1) && check(actual, m.atMost(), -1) && check(actual, m.above(), 2) && check(actual, m.below(), -2); }
    private static boolean check(String actual, String expected, int mode) { if (expected == null) return true; int c = compare(actual, expected); return mode == 0 ? c == 0 : mode == 1 ? c >= 0 : mode == -1 ? c <= 0 : mode == 2 ? c > 0 : c < 0; }
    private static int compare(String a, String b) { String[] x = a.split("\\."), y = b.split("\\."); for (int i = 0; i < Math.max(x.length, y.length); i++) { int c = (i < x.length ? new BigInteger(x[i]) : BigInteger.ZERO).compareTo(i < y.length ? new BigInteger(y[i]) : BigInteger.ZERO); if (c != 0) return c; } return 0; }
    private record Pointer(boolean present, JsonElement value) { }
    private static Evaluation matched() { return new Evaluation(Status.MATCHED, ""); } private static Evaluation no(String d) { return new Evaluation(Status.NOT_MATCHED, d); } private static Evaluation failed(String d) { return new Evaluation(Status.FAILED, d); } private static Evaluation invert(Evaluation e) { return e.status() == Status.FAILED ? e : e.status() == Status.MATCHED ? no("Not condition matched its child.") : matched(); }
    /** Immutable evaluation inputs; target bytes are defensively copied. */
    public record EvaluationContext(Set<String> installedIds, Map<String, String> versions, String serverVersion, String targetPath, byte[] targetBytes, ConditionSourceResolver resolver, List<PatchSource> sources, String targetSourcePackId) {
        public EvaluationContext(List<String> installed, Map<String, String> versions, String serverVersion, String targetPath, byte[] targetBytes, ConditionSourceResolver resolver) {
            this(Set.copyOf(installed), Map.copyOf(versions), serverVersion, targetPath, targetBytes, resolver, List.of(), null);
        }
        public EvaluationContext(Set<String> installedIds, Map<String, String> versions, String serverVersion, String targetPath, byte[] targetBytes, ConditionSourceResolver resolver, List<PatchSource> sources) {
            this(installedIds, versions, serverVersion, targetPath, targetBytes, resolver, sources, null);
        }
        public EvaluationContext(List<String> installed, Map<String, String> versions, String serverVersion, String targetPath, byte[] targetBytes, ConditionSourceResolver resolver, List<PatchSource> sources, String targetSourcePackId) {
            this(Set.copyOf(installed), Map.copyOf(versions), serverVersion, targetPath, targetBytes, resolver, sources, targetSourcePackId);
        }
        public EvaluationContext { installedIds = Set.copyOf(installedIds); versions = Map.copyOf(versions); targetPath = Objects.requireNonNull(targetPath); targetBytes = targetBytes == null ? null : targetBytes.clone(); resolver = Objects.requireNonNull(resolver); sources = List.copyOf(sources); }
        @Override public byte[] targetBytes() { return targetBytes == null ? null : targetBytes.clone(); }
    }
    /** Immutable condition outcome and safe diagnostic. */
    public record Evaluation(Status status, String diagnostic) { public Evaluation { status = Objects.requireNonNull(status); diagnostic = diagnostic == null ? "" : diagnostic; } public boolean matched() { return status == Status.MATCHED; } }
    /** Evaluation outcome status. */
    public enum Status { MATCHED, NOT_MATCHED, FAILED }
}
