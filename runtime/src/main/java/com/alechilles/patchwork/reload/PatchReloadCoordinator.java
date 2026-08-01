package com.alechilles.patchwork.reload;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Serializes authorized reload passes and commits each generated target independently. */
public final class PatchReloadCoordinator {
    /** The only production trigger allowed to start a live generation pass. */
    public enum Trigger { PATCHWORK_RELOAD_COMMAND, SOURCE_EDIT, GENERATED_PACK_OBSERVATION }
    /** Status of one target after its local transaction. */
    public enum TargetState { HOT_RELOADED, ADAPTER_RELOADED, RESTART_REQUIRED, REMOVED, STALE, ROLLBACK_FAILED, FAILED }
    /** Manifest outcome recorded before any target may be touched. */
    public enum ManifestState { NOT_ATTEMPTED, COMMITTED, UNCHANGED, COMMIT_UNCERTAIN }
    /** Fully staged bytes for an explicitly requested generation pass. */
    public record ReloadPlan(byte[] manifestBytes, List<TargetUpdate> updates) {
        public ReloadPlan { manifestBytes = manifestBytes.clone(); updates = List.copyOf(updates); }
        @Override public byte[] manifestBytes() { return manifestBytes.clone(); }
    }
    /** Replacement bytes for one target; a null payload represents intentional removal. */
    public record TargetUpdate(String target, byte[] bytes) {
        public TargetUpdate { target = Objects.requireNonNull(target); bytes = bytes == null ? null : bytes.clone(); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }
    /** Command authorization and a lazy plan prevent unauthorized paths from generating. */
    public record ReloadRequest(Trigger trigger, String permission, Supplier<ReloadPlan> generator) {
        public ReloadRequest { trigger = Objects.requireNonNull(trigger); generator = Objects.requireNonNull(generator); }
        public static ReloadRequest authorized(Supplier<ReloadPlan> generator) { return new ReloadRequest(Trigger.PATCHWORK_RELOAD_COMMAND, "patchwork.admin", generator); }
    }
    /** One externally visible target result. */
    public record TargetOutcome(String target, TargetState state, String adapterId, String diagnostic) { }
    /** Serialized pass result. */
    public record ReloadOutcome(boolean started, long epoch, ManifestState manifestState, List<TargetOutcome> targets, String diagnostic) {
        public ReloadOutcome { targets = List.copyOf(targets); }
    }

    private final Path root;
    private final PatchReloadTracker tracker;
    private final HytalePatchTargetAdapter builtInAdapter;
    private final List<HytalePatchTargetAdapter> hostAdapters;
    private final Duration timeout;
    private final TargetPatchTransaction.MoveStrategy targetMoves;
    private final TargetPatchTransaction.MoveStrategy manifestMoves;
    private final PatchTargetClassifier classifier = new PatchTargetClassifier();
    private long nextEpoch;

    public PatchReloadCoordinator(Path generatedRoot, PatchReloadTracker tracker, HytalePatchTargetAdapter builtInAdapter, List<HytalePatchTargetAdapter> hostAdapters, Duration timeout) {
        this(generatedRoot, tracker, builtInAdapter, hostAdapters, timeout, TargetPatchTransaction.fileMoves(), TargetPatchTransaction.fileMoves());
    }
    PatchReloadCoordinator(Path generatedRoot, PatchReloadTracker tracker, HytalePatchTargetAdapter builtInAdapter, List<HytalePatchTargetAdapter> hostAdapters, Duration timeout, TargetPatchTransaction.MoveStrategy targetMoves, TargetPatchTransaction.MoveStrategy manifestMoves) {
        root = Objects.requireNonNull(generatedRoot).toAbsolutePath().normalize(); this.tracker = Objects.requireNonNull(tracker);
        this.builtInAdapter = Objects.requireNonNull(builtInAdapter); this.hostAdapters = List.copyOf(hostAdapters); this.timeout = Objects.requireNonNull(timeout);
        this.targetMoves = Objects.requireNonNull(targetMoves); this.manifestMoves = Objects.requireNonNull(manifestMoves);
    }

    /** Runs one authorized pass at a time; file and observer events cannot call this method successfully. */
    public synchronized ReloadOutcome reload(ReloadRequest request) {
        if (request.trigger() != Trigger.PATCHWORK_RELOAD_COMMAND || !"patchwork.admin".equals(request.permission())) {
            return new ReloadOutcome(false, nextEpoch, ManifestState.NOT_ATTEMPTED, List.of(), "Live reload requires authorized /patchwork reload.");
        }
        long epoch = ++nextEpoch;
        ReloadPlan plan;
        try { plan = Objects.requireNonNull(request.generator().get(), "Reload generation returned no plan."); }
        catch (Exception failure) { return new ReloadOutcome(true, epoch, ManifestState.NOT_ATTEMPTED, List.of(), "Generation failed: " + failure.getMessage()); }
        ManifestResult manifest = writeManifest(plan.manifestBytes());
        if (manifest.state() != ManifestState.COMMITTED) return new ReloadOutcome(true, epoch, manifest.state(), List.of(), manifest.diagnostic());
        List<TargetOutcome> outcomes = new ArrayList<>();
        TargetPatchTransaction transaction = new TargetPatchTransaction(root, targetMoves);
        for (TargetUpdate update : plan.updates()) outcomes.add(reloadTarget(epoch, transaction, update));
        return new ReloadOutcome(true, epoch, ManifestState.COMMITTED, outcomes, "");
    }

    private TargetOutcome reloadTarget(long epoch, TargetPatchTransaction transaction, TargetUpdate update) {
        try {
            TargetJournalEntry journal = transaction.journal(update.target());
            String expectedHash = TargetJournalEntry.hash(update.bytes()); boolean removal = update.bytes() == null;
            PatchTargetClassifier.Family family = classifier.classify(update.target());
            HytalePatchTargetAdapter.ReloadTarget target = new HytalePatchTargetAdapter.ReloadTarget(epoch, update.target(), expectedHash, removal, family);
            HytalePatchTargetAdapter adapter = select(target);
            try { transaction.apply(update.target(), update.bytes()); }
            catch (Exception failure) { return rollback(epoch, transaction, journal, adapter, family, update.target(), failure.getMessage()); }
            if (adapter == null) return new TargetOutcome(update.target(), TargetState.RESTART_REQUIRED, "", "No verified live reload route.");
            var observation = tracker.expect(epoch, update.target(), expectedHash, removal);
            HytalePatchTargetAdapter.AdapterReply reply;
            try { reply = adapter.reload(target); }
            catch (Exception failure) { tracker.cancel(epoch, update.target(), expectedHash); return rollback(epoch, transaction, journal, adapter, family, update.target(), failure.getMessage()); }
            if (reply == null) { tracker.cancel(epoch, update.target(), expectedHash); return rollback(epoch, transaction, journal, adapter, family, update.target(), "Adapter returned no result."); }
            if (reply.restartRequired()) { tracker.cancel(epoch, update.target(), expectedHash); return new TargetOutcome(update.target(), TargetState.RESTART_REQUIRED, adapter.adapterId(), reply.diagnostic()); }
            if (!reply.accepted() || !await(observation)) {
                tracker.cancel(epoch, update.target(), expectedHash);
                return rollback(epoch, transaction, journal, adapter, family, update.target(), reply.diagnostic());
            }
            TargetState state = removal ? TargetState.REMOVED : adapter == builtInAdapter ? TargetState.HOT_RELOADED : TargetState.ADAPTER_RELOADED;
            return new TargetOutcome(update.target(), state, adapter.adapterId(), "");
        } catch (Exception failure) { return new TargetOutcome(update.target(), TargetState.FAILED, "", failure.getMessage()); }
    }

    private TargetOutcome rollback(long epoch, TargetPatchTransaction transaction, TargetJournalEntry journal, HytalePatchTargetAdapter adapter, PatchTargetClassifier.Family family, String target, String diagnostic) {
        try {
            transaction.rollback(journal);
            if (adapter == null) return new TargetOutcome(target, TargetState.ROLLBACK_FAILED, "", diagnostic);
            boolean removal = journal.oldBytes() == null;
            var expected = tracker.expect(epoch, target, journal.oldHash(), removal);
            HytalePatchTargetAdapter.AdapterReply reply = adapter.reload(new HytalePatchTargetAdapter.ReloadTarget(epoch, target, journal.oldHash(), removal, family));
            if (reply.accepted() && await(expected)) return new TargetOutcome(target, TargetState.STALE, adapter.adapterId(), diagnostic);
            tracker.cancel(epoch, target, journal.oldHash());
        } catch (Exception ignored) { }
        return new TargetOutcome(target, TargetState.ROLLBACK_FAILED, adapter.adapterId(), diagnostic);
    }

    private HytalePatchTargetAdapter select(HytalePatchTargetAdapter.ReloadTarget target) {
        if (target.family() != PatchTargetClassifier.Family.CUSTOM && target.family() != PatchTargetClassifier.Family.RESTART_REQUIRED && builtInAdapter.supports(target)) return builtInAdapter;
        for (HytalePatchTargetAdapter adapter : hostAdapters) if (adapter.supports(target)) return adapter;
        return null;
    }
    private boolean await(java.util.concurrent.CompletableFuture<PatchReloadTracker.Outcome> expected) {
        try { return expected.get(timeout.toMillis(), TimeUnit.MILLISECONDS) != PatchReloadTracker.Outcome.FAILED; }
        catch (Exception timeoutOrFailure) { return false; }
    }
    private ManifestResult writeManifest(byte[] bytes) {
        Path manifest = root.resolve("patchwork-manifest.json"); Path temporary = null;
        try { Files.createDirectories(root); temporary = Files.createTempFile(root, ".patchwork-manifest-", ".tmp"); Files.write(temporary, bytes); try { manifestMoves.atomicMove(temporary, manifest); }
            catch (AtomicMoveNotSupportedException unsupported) { manifestMoves.nonAtomicMove(temporary, manifest); }
            return new ManifestResult(ManifestState.COMMITTED, "");
        } catch (Exception failure) {
            try { if (Files.exists(manifest) && java.util.Arrays.equals(bytes, Files.readAllBytes(manifest))) return new ManifestResult(ManifestState.COMMIT_UNCERTAIN, "Manifest move reported failure after desired bytes became visible."); }
            catch (IOException ignored) { }
            return new ManifestResult(ManifestState.UNCHANGED, "Manifest commit failed: " + failure.getMessage());
        } finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
    private record ManifestResult(ManifestState state, String diagnostic) { }
}
