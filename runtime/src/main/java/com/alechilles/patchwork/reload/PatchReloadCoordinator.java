package com.alechilles.patchwork.reload;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import com.alechilles.patchwork.generation.GeneratedPackManifest;

/** Serializes authorized reload passes and commits each generated target independently. */
public final class PatchReloadCoordinator {
    /** The only production trigger allowed to start a live generation pass. */
    public enum Trigger { PATCHWORK_RELOAD_COMMAND, SOURCE_EDIT, GENERATED_PACK_OBSERVATION }
    /** Status of one target after its local transaction. */
    public enum TargetState { HOT_RELOADED, ADAPTER_RELOADED, RESTART_REQUIRED, REMOVED, STALE, ROLLBACK_FAILED, FAILED }
    /** Manifest outcome recorded before any target may be touched. */
    public enum ManifestState { NOT_ATTEMPTED, COMMITTED, UNCHANGED, COMMIT_UNCERTAIN }
    /** Final exact inventory reconciliation state. */
    public enum IntegrityState { NOT_ATTEMPTED, RECONCILED, FAILED, UNCERTAIN }
    /** Fully staged bytes for an explicitly requested generation pass. */
    public record ReloadPlan(byte[] hytaleManifestBytes, List<TargetUpdate> updates) {
        public ReloadPlan { hytaleManifestBytes = hytaleManifestBytes.clone(); updates = List.copyOf(updates); java.util.Set<String> targets = new java.util.HashSet<>(); for (TargetUpdate update : updates) if (!targets.add(update.target())) throw new IllegalArgumentException("Duplicate reload target: " + update.target()); }
        @Override public byte[] hytaleManifestBytes() { return hytaleManifestBytes.clone(); }
    }
    /** Replacement bytes for one target; a null payload represents intentional removal. */
    public record TargetUpdate(String target, byte[] bytes) {
        public TargetUpdate { target = canonicalTarget(target); bytes = bytes == null ? null : bytes.clone(); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }
    private static String canonicalTarget(String target) {
        if (target == null || target.isBlank() || target.indexOf('\\') >= 0 || target.startsWith("/") || target.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("Reload target is not canonical.");
        String[] parts = target.split("/", -1); for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Reload target is not canonical.");
        if (target.equals("manifest.json") || target.equals(GeneratedPackManifest.FILE_NAME)) throw new IllegalArgumentException("Reload target reserves generated pack manifests.");
        return target;
    }
    /** Command authorization and a lazy plan prevent unauthorized paths from generating. */
    public record ReloadRequest(Trigger trigger, String permission, Supplier<ReloadPlan> generator) {
        public ReloadRequest { trigger = Objects.requireNonNull(trigger); generator = Objects.requireNonNull(generator); }
        public static ReloadRequest authorized(Supplier<ReloadPlan> generator) { return new ReloadRequest(Trigger.PATCHWORK_RELOAD_COMMAND, "patchwork.admin", generator); }
    }
    /** One externally visible target result. */
    public record TargetOutcome(String target, TargetState state, String adapterId, String diagnostic, TargetJournalEntry rollbackEvidence, Path rollbackEvidencePath, boolean restartRequired) { }
    /** Serialized pass result. */
    public record ReloadOutcome(boolean started, long epoch, ManifestState manifestState, List<TargetOutcome> targets, IntegrityState integrityState, String diagnostic) {
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
    private volatile boolean accepting = true;
    private volatile long ownershipEpoch;
    private final Object lifecycleLock = new Object();
    private volatile boolean inProgress;
    private volatile String activePassToken;
    private final String coordinatorToken = java.util.UUID.randomUUID().toString();

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
        if (inProgress || !accepting) return new ReloadOutcome(false, nextEpoch, ManifestState.NOT_ATTEMPTED, List.of(), IntegrityState.NOT_ATTEMPTED, "Reload is fenced or already in progress.");
        if (request.trigger() != Trigger.PATCHWORK_RELOAD_COMMAND || !"patchwork.admin".equals(request.permission())) {
            return new ReloadOutcome(false, nextEpoch, ManifestState.NOT_ATTEMPTED, List.of(), IntegrityState.NOT_ATTEMPTED, "Live reload requires authorized /patchwork reload.");
        }
        long epoch = ++nextEpoch; String passToken = coordinatorToken + ":" + ownershipEpoch + ":" + epoch; activePassToken = passToken; tracker.activate(passToken); inProgress = true;
        try {
        ReloadPlan plan;
        try { plan = Objects.requireNonNull(request.generator().get(), "Reload generation returned no plan."); }
        catch (Exception failure) { return new ReloadOutcome(true, epoch, ManifestState.NOT_ATTEMPTED, List.of(), IntegrityState.NOT_ATTEMPTED, "Generation failed: " + failure.getMessage()); }
        if (!startOperation(passToken)) return new ReloadOutcome(true, epoch, ManifestState.NOT_ATTEMPTED, List.of(), IntegrityState.NOT_ATTEMPTED, "Reload revoked during generation.");
        ManifestResult manifest = writeManifest("manifest.json", plan.hytaleManifestBytes());
        if (manifest.state() != ManifestState.COMMITTED) return new ReloadOutcome(true, epoch, manifest.state(), List.of(), IntegrityState.NOT_ATTEMPTED, manifest.diagnostic());
        List<TargetOutcome> outcomes = new ArrayList<>();
        TargetPatchTransaction transaction = new TargetPatchTransaction(root, targetMoves);
        for (TargetUpdate update : plan.updates()) { if (!accepting) break; outcomes.add(reloadTarget(passToken, epoch, transaction, update)); }
        IntegrityResult integrity = reconcileIntegrity(passToken);
        return new ReloadOutcome(true, epoch, ManifestState.COMMITTED, outcomes, integrity.state(), (accepting ? "" : "Reload revoked. ") + integrity.diagnostic());
        } finally { tracker.fence(passToken); activePassToken = null; synchronized (lifecycleLock) { inProgress = false; lifecycleLock.notifyAll(); } }
    }

    /** Activates this coordinator for an ownership epoch. */
    public void activate(long epoch) { synchronized (lifecycleLock) { if (epoch > ownershipEpoch) { ownershipEpoch = epoch; accepting = true; } } }
    /** Revokes only the current-or-newer ownership epoch and immediately cancels pending waits. */
    public void revoke(long epoch) { synchronized (lifecycleLock) { if (epoch >= ownershipEpoch) { ownershipEpoch = epoch; accepting = false; if (activePassToken != null) tracker.fence(activePassToken); tracker.cancelAll("Reload owner was revoked."); } } }
    /** Waits only a bounded period for a fenced in-flight pass to leave its current operation. */
    public boolean drain(Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        synchronized (lifecycleLock) {
            while (inProgress) {
                long remaining = deadline - System.nanoTime(); if (remaining <= 0) return false;
                try { long millis = Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)); lifecycleLock.wait(millis); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            }
            return true;
        }
    }

    private TargetOutcome reloadTarget(String token, long epoch, TargetPatchTransaction transaction, TargetUpdate update) {
        try {
            TargetJournalEntry journal = transaction.journal(update.target());
            String expectedHash = TargetJournalEntry.hash(update.bytes()); boolean removal = update.bytes() == null;
            PatchTargetClassifier.Family family = classifier.classify(update.target());
            HytalePatchTargetAdapter.ReloadTarget target = new HytalePatchTargetAdapter.ReloadTarget(token, epoch, update.target(), expectedHash, removal, family);
            HytalePatchTargetAdapter adapter;
            java.util.concurrent.CompletableFuture<PatchReloadTracker.Outcome> observation;
            synchronized (lifecycleLock) {
                if (!hasLease(token)) return outcome(update.target(), TargetState.FAILED, "", "Reload lease was revoked.", null, false);
                adapter = select(target);
                observation = tracker.expect(token, epoch, update.target(), expectedHash, removal);
            }
            try { transaction.apply(update.target(), update.bytes()); }
            catch (Exception failure) { tracker.cancel(token, epoch, update.target(), expectedHash); return rollback(token, epoch, transaction, journal, adapter, family, update.target(), failure.getMessage()); }
            if (adapter == null) { tracker.cancel(token, epoch, update.target(), expectedHash); return outcome(update.target(), TargetState.RESTART_REQUIRED, "", "No verified live reload route.", null, true); }
            synchronized (lifecycleLock) {
                if (!hasLease(token)) { tracker.cancel(token, epoch, update.target(), expectedHash); return rollback(token, epoch, transaction, journal, adapter, family, update.target(), "Reload revoked before adapter start."); }
            }
            HytalePatchTargetAdapter.AdapterReply reply;
            try { reply = adapter.reload(target); }
            catch (Exception failure) { tracker.cancel(token, epoch, update.target(), expectedHash); return rollback(token, epoch, transaction, journal, adapter, family, update.target(), failure.getMessage()); }
            if (reply == null) { tracker.cancel(token, epoch, update.target(), expectedHash); return rollback(token, epoch, transaction, journal, adapter, family, update.target(), "Adapter returned no result."); }
            if (reply.restartRequired()) { tracker.cancel(token, epoch, update.target(), expectedHash); return outcome(update.target(), TargetState.RESTART_REQUIRED, adapter.adapterId(), reply.diagnostic(), null, true); }
            if (!reply.accepted() || !await(observation)) {
                tracker.cancel(token, epoch, update.target(), expectedHash);
                return rollback(token, epoch, transaction, journal, adapter, family, update.target(), reply.diagnostic());
            }
            TargetState state = removal ? TargetState.REMOVED : adapter == builtInAdapter ? TargetState.HOT_RELOADED : TargetState.ADAPTER_RELOADED;
            return outcome(update.target(), state, adapter.adapterId(), "", null, false);
        } catch (Exception failure) { return outcome(update.target(), TargetState.FAILED, "", failure.getMessage(), null, false); }
    }
    private boolean hasLease(String token) { return accepting && token.equals(activePassToken); }
    /** Atomically records that a pass-owned operation began before a revocation can fence it. */
    private boolean startOperation(String token) { synchronized (lifecycleLock) { return hasLease(token); } }

    private TargetOutcome rollback(String token, long epoch, TargetPatchTransaction transaction, TargetJournalEntry journal, HytalePatchTargetAdapter adapter, PatchTargetClassifier.Family family, String target, String diagnostic) {
        boolean expectationRegistered = false;
        try {
            boolean removal = journal.oldBytes() == null;
            java.util.concurrent.CompletableFuture<PatchReloadTracker.Outcome> expected;
            boolean confirmationAvailable;
            synchronized (lifecycleLock) {
                confirmationAvailable = hasLease(token);
                if (confirmationAvailable) { expected = tracker.expect(token, epoch, target, journal.oldHash(), removal); expectationRegistered = true; }
                else expected = null;
            }
            transaction.rollback(journal);
            if (!confirmationAvailable) return outcome(target, TargetState.ROLLBACK_FAILED, "", diagnostic + " Reload revoked before rollback confirmation.", journal, true);
            if (adapter == null) return outcome(target, TargetState.ROLLBACK_FAILED, "", diagnostic, journal, true);
            synchronized (lifecycleLock) { if (!hasLease(token)) return outcome(target, TargetState.ROLLBACK_FAILED, "", diagnostic + " Reload revoked before rollback adapter start.", journal, true); }
            HytalePatchTargetAdapter.AdapterReply reply = adapter.reload(new HytalePatchTargetAdapter.ReloadTarget(token, epoch, target, journal.oldHash(), removal, family));
            if (reply != null && reply.accepted() && !reply.restartRequired() && await(expected)) return outcome(target, TargetState.STALE, adapter.adapterId(), diagnostic, null, false);
        } catch (Exception failure) { diagnostic = diagnostic + " Rollback failed: " + failure.getMessage(); }
        finally { if (expectationRegistered) tracker.cancel(token, epoch, target, journal.oldHash()); }
        return outcome(target, TargetState.ROLLBACK_FAILED, adapter == null ? "" : adapter.adapterId(), diagnostic, journal, true);
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
    private TargetOutcome outcome(String target, TargetState state, String adapterId, String diagnostic, TargetJournalEntry evidence, boolean restartRequired) {
        Path evidencePath = evidence == null ? null : preserveEvidence(target, evidence);
        return new TargetOutcome(target, state, adapterId, diagnostic + (evidence != null && evidencePath == null ? " Evidence persistence failed." : ""), evidence, evidencePath, restartRequired);
    }
    private Path preserveEvidence(String target, TargetJournalEntry evidence) {
        try {
            Path diagnostics = root.getParent().resolve("Diagnostics").normalize(); TargetPatchTransaction.verifySafePath(root.getParent()); Files.createDirectories(diagnostics); TargetPatchTransaction.verifySafePath(diagnostics); var diagnosticsAncestry = TargetPatchTransaction.captureAncestry(diagnostics);
            String safe = safePathToken(target); String hash = safePathToken(evidence.oldHash()); String name = "reload-" + nextEpoch + "-" + safe + "-" + hash.substring(0, Math.min(12, hash.length())) + "-" + java.util.UUID.randomUUID(); Path directory = Files.createDirectory(diagnostics.resolve(name)); TargetPatchTransaction.verifySafePath(directory);
            TargetPatchTransaction.requireSameAncestry(diagnostics, diagnosticsAncestry); writeEvidenceFile(directory, "metadata.txt", ("Epoch=" + nextEpoch + "\nTarget=" + target + "\nHash=" + evidence.oldHash() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (evidence.oldBytes() != null) { TargetPatchTransaction.requireSameAncestry(diagnostics, diagnosticsAncestry); writeEvidenceFile(directory, "old-bytes.bin", evidence.oldBytes()); }
            return directory;
        } catch (Exception ignored) { return null; }
    }
    private static String safePathToken(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static void writeEvidenceFile(Path directory, String fileName, byte[] bytes) throws IOException {
        TargetPatchTransaction.verifySafePath(directory); var ancestry = TargetPatchTransaction.captureAncestry(directory); Path finalFile = directory.resolve(safePathToken(fileName)); Path temporary = Files.createTempFile(directory, ".patchwork-evidence-", ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            ByteBuffer content = ByteBuffer.wrap(bytes); while (content.hasRemaining()) channel.write(content); channel.force(true);
        }
        try { TargetPatchTransaction.requireSameAncestry(directory, ancestry); Files.move(temporary, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { TargetPatchTransaction.requireSameAncestry(directory, ancestry); Files.move(temporary, finalFile, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
        TargetPatchTransaction.requireSameAncestry(directory, ancestry); TargetPatchTransaction.verifySafePath(finalFile); if (!java.util.Arrays.equals(bytes, TargetPatchTransaction.readStable(finalFile))) throw new IOException("Rollback evidence verification failed.");
    }
    private ManifestResult writeManifest(String fileName, byte[] bytes) {
        Path manifest = root.resolve(fileName); Path temporary = null;
        try { TargetPatchTransaction.verifySafePath(root); TargetPatchTransaction.verifySafePath(manifest); Files.createDirectories(root); TargetPatchTransaction.verifySafePath(root); temporary = Files.createTempFile(root, ".patchwork-manifest-", ".tmp"); Files.write(temporary, bytes); var ancestry = TargetPatchTransaction.captureAncestry(root); manifestMoves.beforeMutation(manifest); TargetPatchTransaction.requireSameAncestry(root, ancestry); TargetPatchTransaction.verifySafePath(manifest); try { manifestMoves.atomicMove(temporary, manifest); }
            catch (AtomicMoveNotSupportedException unsupported) { manifestMoves.nonAtomicMove(temporary, manifest); }
            TargetPatchTransaction.requireSameAncestry(root, ancestry); TargetPatchTransaction.verifySafePath(manifest);
            return new ManifestResult(ManifestState.COMMITTED, "");
        } catch (Exception failure) {
            try { if (Files.exists(manifest, java.nio.file.LinkOption.NOFOLLOW_LINKS) && java.util.Arrays.equals(bytes, TargetPatchTransaction.readStable(manifest))) return new ManifestResult(ManifestState.COMMIT_UNCERTAIN, "Manifest move reported failure after desired bytes became visible."); }
            catch (IOException ignored) { }
            return new ManifestResult(ManifestState.UNCHANGED, "Manifest commit failed: " + failure.getMessage());
        } finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
    private IntegrityResult reconcileIntegrity(String token) {
        try {
            List<GeneratedPackManifest.Entry> entries = scanInventoryEntries();
            byte[] manifestBytes = new GeneratedPackManifest(entries).bytes();
            if (!startOperation(token)) return new IntegrityResult(IntegrityState.UNCERTAIN, "Reload revoked before inventory commit.");
            ManifestResult result = writeManifest(GeneratedPackManifest.FILE_NAME, manifestBytes);
            if (result.state() != ManifestState.COMMITTED) return new IntegrityResult(IntegrityState.UNCERTAIN, result.diagnostic());
            if (!sameInventory(entries, scanInventoryEntries())) return new IntegrityResult(IntegrityState.UNCERTAIN, "Generated inventory changed during reconciliation.");
            if (!java.util.Arrays.equals(manifestBytes, TargetPatchTransaction.readStable(root.resolve(GeneratedPackManifest.FILE_NAME)))) return new IntegrityResult(IntegrityState.UNCERTAIN, "Generated inventory manifest changed during reconciliation.");
            return new IntegrityResult(IntegrityState.RECONCILED, "");
        } catch (Exception failure) { return new IntegrityResult(IntegrityState.FAILED, "Integrity reconciliation failed: " + failure.getMessage()); }
    }
    private List<GeneratedPackManifest.Entry> scanInventoryEntries() throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(path -> {
                try { TargetPatchTransaction.verifySafePath(path); return Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS); } catch (IOException unsafe) { throw new IllegalStateException(unsafe); }
            }).filter(path -> { String relative = root.relativize(path).toString().replace('\\', '/'); return !relative.equals("manifest.json") && !relative.equals(GeneratedPackManifest.FILE_NAME); }).map(path -> {
                try { return new GeneratedPackManifest.Entry(root.relativize(path).toString().replace('\\', '/'), TargetPatchTransaction.readStable(path)); }
                catch (IOException failure) { throw new IllegalStateException(failure); }
            }).toList();
        }
    }
    private static boolean sameInventory(List<GeneratedPackManifest.Entry> expected, List<GeneratedPackManifest.Entry> actual) {
        if (expected.size() != actual.size()) return false;
        for (int index = 0; index < expected.size(); index++) if (!expected.get(index).target().equals(actual.get(index).target()) || !java.util.Arrays.equals(expected.get(index).bytes(), actual.get(index).bytes())) return false;
        return true;
    }
    private record ManifestResult(ManifestState state, String diagnostic) { }
    private record IntegrityResult(IntegrityState state, String diagnostic) { }
}
