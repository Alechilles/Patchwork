package com.alechilles.patchwork.reload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Correlates Hytale provider/path notifications with one pending Patchwork
 * transaction. A disk write alone never completes an expectation.
 */
public final class HytaleReloadEvidenceCorrelator {
    public record Expectation(String token, long reloadEpoch, String target, String expectedHash, boolean removal,
                              String expectedProvider) {
        public Expectation {
            token = Objects.requireNonNull(token, "token");
            target = Objects.requireNonNull(target, "target");
            expectedHash = Objects.requireNonNull(expectedHash, "expectedHash");
            expectedProvider = Objects.requireNonNull(expectedProvider, "expectedProvider");
        }
    }

    public record Evidence(String token, long epoch, String target, String provider, String assetPath, boolean removal) {
        public Evidence {
            token = Objects.requireNonNull(token, "token");
            target = Objects.requireNonNull(target, "target");
            assetPath = Objects.requireNonNull(assetPath, "assetPath");
        }
    }

    private final PatchReloadTracker tracker;
    private final Path generatedRoot;
    private final Map<Key, Expectation> pending = new HashMap<>();

    public HytaleReloadEvidenceCorrelator(PatchReloadTracker tracker) {
        this(tracker, null);
    }

    public HytaleReloadEvidenceCorrelator(PatchReloadTracker tracker, Path generatedRoot) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.generatedRoot = generatedRoot == null ? null : generatedRoot.toAbsolutePath().normalize();
    }

    /** Registers provider/path evidence required for one coordinator target. */
    public synchronized void expect(Expectation expectation) {
        Objects.requireNonNull(expectation, "expectation");
        Key key = new Key(expectation.token(), expectation.reloadEpoch(), expectation.target(), expectation.expectedHash());
        if (pending.putIfAbsent(key, expectation) != null) throw new IllegalStateException("Hytale evidence already pending for " + expectation.target());
    }

    /** Cancels a pending evidence requirement when the transaction is rolled back or fenced. */
    public synchronized void cancel(String token, long epoch, String target, String expectedHash) {
        pending.remove(new Key(token, epoch, target, expectedHash));
    }

    /** Cancels all pending expectations owned by a fenced event bridge. */
    public synchronized void cancelAll() {
        pending.clear();
    }

    /**
     * Matches one generated-provider monitor path against every pending target.
     * A monitor event does not carry Patchwork's transaction token, so this
     * method performs the same exact checks as {@link #confirm(Evidence)} while
     * retaining token/epoch/hash correlation internally.
     */
    public synchronized boolean confirmAny(String provider, String assetPath, boolean removal) {
        for (Expectation expected : java.util.List.copyOf(pending.values())) {
            if (confirm(new Evidence(expected.token(), expected.reloadEpoch(), expected.target(), provider, assetPath, removal))) return true;
        }
        return false;
    }

    /**
     * Compatibility overload for callers that still pass the elected host
     * epoch. Hytale monitor callbacks do not carry the coordinator reload
     * epoch, so matching is intentionally performed against each pending
     * expectation rather than comparing this value.
     */
    public boolean confirmAny(long ignoredHostEpoch, String provider, String assetPath, boolean removal) {
        return confirmAny(provider, assetPath, removal);
    }

    /**
     * Accepts only the expected generated provider and asset path. If a live
     * generated root was supplied, its bytes must also match the expected hash
     * (or be absent for a removal) before the tracker is completed.
     */
    public synchronized boolean confirm(Evidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        Key found = null;
        Expectation expected = null;
        for (Map.Entry<Key, Expectation> entry : pending.entrySet()) {
            Expectation candidate = entry.getValue();
            if (!candidate.token().equals(evidence.token()) || candidate.reloadEpoch() != evidence.epoch()
                    || !candidate.target().equals(evidence.target())) continue;
            found = entry.getKey(); expected = candidate; break;
        }
        boolean providerMatches = expected != null && (expected.removal()
                ? !expected.expectedProvider().equals(evidence.provider())
                : expected.expectedProvider().equals(evidence.provider()));
        if (expected == null || !providerMatches
                || expected.removal() != evidence.removal()
                || !pathMatches(expected.target(), evidence.assetPath())
                || !diskMatches(expected)) return false;
        if (!tracker.record(new PatchReloadTracker.Observation(expected.token(), expected.reloadEpoch(), expected.target(), expected.expectedHash(), expected.removal() ? PatchReloadTracker.Outcome.REMOVED : PatchReloadTracker.Outcome.LOADED))) {
            pending.remove(found);
            return false;
        }
        pending.remove(found);
        return true;
    }

    /** Convenience overload for bridge handlers that already have an exact target. */
    public boolean confirm(String token, long epoch, String target, String provider, String assetPath, boolean removal) {
        return confirm(new Evidence(token, epoch, target, provider, assetPath, removal));
    }

    private boolean diskMatches(Expectation expected) {
        if (generatedRoot == null) return true;
        Path target = generatedRoot.resolve(expected.target()).normalize();
        if (!target.startsWith(generatedRoot)) return false;
        if (expected.removal()) return !Files.exists(target);
        try {
            return Files.isRegularFile(target) && expected.expectedHash().equals(TargetJournalEntry.hash(Files.readAllBytes(target)));
        } catch (IOException failure) {
            return false;
        }
    }

    private static boolean pathMatches(String expected, String observed) {
        String left = normalize(expected);
        String right = normalize(observed);
        return left.equals(right) || right.endsWith("/" + left) || left.endsWith("/" + right);
    }

    private static String normalize(String value) {
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    private record Key(String token, long epoch, String target, String hash) { }
}
