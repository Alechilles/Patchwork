package com.alechilles.patchwork.coordinator;

import java.nio.file.Path;
import java.util.Objects;
import java.math.BigInteger;

/** Immutable runtime descriptor used for deterministic process-wide election. */
final class PatchworkRuntimeCandidate implements Comparable<PatchworkRuntimeCandidate> {
    private final String providerId;
    private final PatchworkRuntimeOrigin origin;
    private final String runtimeVersion;
    private final SemVer semanticVersion;
    private final int coordinatorAbi;
    private final String providerPluginId;
    private final String providerPluginVersion;
    private final Path sourceJarPath;
    private final Path sharedDataRoot;
    private final PatchworkCoordinatorBridge bridge;

    PatchworkRuntimeCandidate(String providerId, PatchworkRuntimeOrigin origin, String runtimeVersion, int coordinatorAbi,
                                     String providerPluginId, String providerPluginVersion, Path sourceJarPath, Path providerDataRoot,
                                     PatchworkCoordinatorBridge bridge) {
        this.providerId = require(providerId); this.origin = Objects.requireNonNull(origin); this.runtimeVersion = require(runtimeVersion);
        this.semanticVersion = SemVer.parse(this.runtimeVersion); this.coordinatorAbi = coordinatorAbi; this.providerPluginId = require(providerPluginId); this.providerPluginVersion = require(providerPluginVersion);
        this.sourceJarPath = Objects.requireNonNull(sourceJarPath).toAbsolutePath().normalize();
        this.sharedDataRoot = canonicalSharedRoot(Objects.requireNonNull(providerDataRoot)); this.bridge = Objects.requireNonNull(bridge);
    }

    String providerId() { return providerId; } PatchworkRuntimeOrigin origin() { return origin; } String runtimeVersion() { return runtimeVersion; }
    int coordinatorAbi() { return coordinatorAbi; } String providerPluginId() { return providerPluginId; } String providerPluginVersion() { return providerPluginVersion; }
    Path sourceJarPath() { return sourceJarPath; } Path sharedDataRoot() { return sharedDataRoot; } PatchworkCoordinatorBridge bridge() { return bridge; }
    boolean compatibleWith(int abi) { return coordinatorAbi == abi; }

    static Path canonicalSharedRoot(Path providerDataRoot) {
        Path normalized = providerDataRoot.toAbsolutePath().normalize();
        for (Path current = normalized; current != null; current = current.getParent()) {
            Path name = current.getFileName();
            if (name != null && name.toString().equalsIgnoreCase("mods")) return current.resolve("Alechilles_Patchwork").normalize();
        }
        return normalized.resolve("Alechilles_Patchwork").normalize();
    }

    @Override public int compareTo(PatchworkRuntimeCandidate other) {
        int comparison = other.semanticVersion.compareTo(semanticVersion);
        if (comparison != 0) return comparison;
        comparison = origin.compareTo(other.origin);
        if (comparison != 0) return comparison;
        comparison = providerPluginId.compareTo(other.providerPluginId);
        if (comparison != 0) return comparison;
        return sourceJarPath.toString().compareTo(other.sourceJarPath.toString());
    }

    private record SemVer(BigInteger major, BigInteger minor, BigInteger patch, String[] prerelease) implements Comparable<SemVer> {
        static SemVer parse(String text) {
            String[] build = text.split("\\+", -1); if (build.length > 2 || (build.length == 2 && (!build[1].matches("[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*")))) throw new IllegalArgumentException("Invalid semantic version: " + text); String[] split = build[0].split("-", 2); String[] core = split[0].split("\\.", -1);
            if (core.length != 3) throw new IllegalArgumentException("Invalid semantic version: " + text);
            try { return new SemVer(number(core[0]), number(core[1]), number(core[2]), split.length == 1 ? new String[0] : identifiers(split[1], text)); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid semantic version: " + text, exception); }
        }
        private static BigInteger number(String part) { if (!part.matches("0|[1-9][0-9]*")) throw new NumberFormatException(); return new BigInteger(part); }
        private static String[] identifiers(String part, String text) { String[] values = part.split("\\.", -1); for (String value : values) if (!value.matches("[0-9A-Za-z-]+") || (value.length() > 1 && value.charAt(0) == '0' && value.chars().allMatch(Character::isDigit))) throw new IllegalArgumentException("Invalid semantic version: " + text); return values; }
        public int compareTo(SemVer other) { BigInteger[] left = {major, minor, patch}; BigInteger[] right = {other.major, other.minor, other.patch}; for (int i = 0; i < 3; i++) { int core = left[i].compareTo(right[i]); if (core != 0) return core; } if (prerelease.length == 0 || other.prerelease.length == 0) return prerelease.length == other.prerelease.length ? 0 : prerelease.length == 0 ? 1 : -1; for (int i = 0; i < Math.min(prerelease.length, other.prerelease.length); i++) { String a = prerelease[i], b = other.prerelease[i]; boolean an = a.chars().allMatch(Character::isDigit), bn = b.chars().allMatch(Character::isDigit); int c = an && bn ? new BigInteger(a).compareTo(new BigInteger(b)) : an ? -1 : bn ? 1 : a.compareTo(b); if (c != 0) return c; } return Integer.compare(prerelease.length, other.prerelease.length); }
    }

    private static String require(String value) {
        String stripped = Objects.requireNonNull(value, "value").strip();
        if (stripped.isEmpty()) throw new IllegalArgumentException("Candidate strings must not be blank");
        return stripped;
    }
}
