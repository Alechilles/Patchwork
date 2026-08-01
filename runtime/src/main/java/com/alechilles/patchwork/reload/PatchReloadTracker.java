package com.alechilles.patchwork.reload;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Correlates asynchronous asset observations with one exact reload expectation. */
public final class PatchReloadTracker {
    /** Hash token shared with transactions for an intentionally absent target. */
    public static final String REMOVED_HASH = TargetJournalEntry.REMOVED_HASH;
    /** Observer results understood by the reload transaction. */
    public enum Outcome { LOADED, REMOVED, FAILED }
    /** Immutable observation forwarded by a built-in or host observer. */
    public record Observation(long epoch, String target, String expectedHash, Outcome outcome) { }
    private final Map<Key, Pending> pending = new HashMap<>();

    /** Begins waiting for one target's expected live state. */
    public synchronized CompletableFuture<Outcome> expect(long epoch, String target, String expectedHash, boolean removal) {
        Key key = new Key(epoch, target, expectedHash);
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        Outcome expectedOutcome = removal ? Outcome.REMOVED : Outcome.LOADED;
        if (pending.putIfAbsent(key, new Pending(expectedOutcome, result)) != null) throw new IllegalStateException("A reload expectation is already pending for " + target + ".");
        return result;
    }

    /** Completes only the exact current epoch, target, and expected-hash expectation. */
    public synchronized boolean record(Observation observation) {
        Pending pendingObservation = pending.remove(new Key(observation.epoch(), observation.target(), observation.expectedHash()));
        if (pendingObservation == null) return false;
        if (observation.outcome() == Outcome.FAILED) {
            pendingObservation.future().completeExceptionally(new IllegalStateException("Observer rejected " + observation.target()));
            return true;
        }
        if (observation.outcome() != pendingObservation.expectedOutcome()) {
            pendingObservation.future().completeExceptionally(new IllegalStateException("Observer reported the wrong state for " + observation.target()));
            return false;
        }
        pendingObservation.future().complete(observation.outcome());
        return true;
    }

    /** Cancels an expectation after timeout or an adapter failure. */
    public synchronized void cancel(long epoch, String target, String expectedHash) {
        pending.remove(new Key(epoch, target, expectedHash));
    }

    /** Fences all in-flight observers when a runtime owner is revoked. */
    public synchronized void cancelAll(String reason) {
        for (Pending pendingObservation : pending.values()) pendingObservation.future().completeExceptionally(new IllegalStateException(reason));
        pending.clear();
    }

    private record Key(long epoch, String target, String expectedHash) { }
    private record Pending(Outcome expectedOutcome, CompletableFuture<Outcome> future) { }
}
