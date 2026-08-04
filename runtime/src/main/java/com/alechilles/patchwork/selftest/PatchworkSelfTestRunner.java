package com.alechilles.patchwork.selftest;

import com.alechilles.patchwork.conditions.ConditionDocumentCache;
import com.alechilles.patchwork.conditions.ConditionSourceResolver;
import com.alechilles.patchwork.conditions.ModDataRootRegistry;
import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.alechilles.patchwork.generation.GeneratedPackLayout;
import com.alechilles.patchwork.generation.PatchGenerationService;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-use runner for real, Patchwork-neutral generation wholly below one UUID self-test directory.
 * Cancellation remains effective before or during its sole admitted run.
 * Identity and no-follow checks reject every observable path replacement; hostile same-process
 * Java code is outside this integrity boundary because it already has equivalent filesystem access.
 */
public final class PatchworkSelfTestRunner {
    private final GeneratedPackLayout layout;
    private final PatchworkSelfTestReloadHandle reloadHandle;
    private final FileOperations files;
    private final PhaseHook phases;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean runAdmitted = new AtomicBoolean();

    /** Creates one single-use runner without an isolated reload callback. */
    public PatchworkSelfTestRunner(GeneratedPackLayout layout) { this(layout, null); }
    /** Creates one single-use runner with the supplied isolated reload callback. */
    public PatchworkSelfTestRunner(GeneratedPackLayout layout, PatchworkSelfTestReloadHandle reloadHandle) { this(layout, reloadHandle, Files::delete, (phase, run) -> { }); }
    PatchworkSelfTestRunner(GeneratedPackLayout layout, PatchworkSelfTestReloadHandle reloadHandle, FileOperations files) {
        this(layout, reloadHandle, files, (phase, run) -> { });
    }
    PatchworkSelfTestRunner(GeneratedPackLayout layout, PatchworkSelfTestReloadHandle reloadHandle, FileOperations files, PhaseHook phases) {
        this.layout = Objects.requireNonNull(layout); this.reloadHandle = reloadHandle; this.files = Objects.requireNonNull(files); this.phases = Objects.requireNonNull(phases);
    }
    public void cancel() { cancelled.set(true); if (reloadHandle != null) reloadHandle.cancel(); }

    public PatchworkSelfTestResult run(PatchworkSelfTestPack pack) {
        if (!runAdmitted.compareAndSet(false, true)) throw new IllegalStateException("Patchwork self-test runners are single-use.");
        Objects.requireNonNull(pack, "pack");
        Path root = layout.selfTestRoot().toAbsolutePath().normalize();
        Path run = root.resolve(UUID.randomUUID().toString()).normalize();
        List<String> outcomes = new ArrayList<>(); List<String> generated = new ArrayList<>(); List<PatchworkSelfTestResult.CaseOutcome> cases = new ArrayList<>();
        boolean attempted = false, cleaned = false, started = false, completed = false, wasCancelled = false;
        String diagnostic = ""; PatchworkSelfTestResult.GenerationOutcome generation = PatchworkSelfTestResult.GenerationOutcome.FAILED;
        PatchworkSelfTestReloadHandle.ReloadOutcome reload = PatchworkSelfTestReloadHandle.ReloadOutcome.RESTART_REQUIRED;
        OwnedRunGuard guard = null;
        try {
            checkCancelled(); layout.createSafeDataRoot(); ensureDirectory(root); ensureRun(root, run); ensureDirectory(run); started = true;
            Path source = child(run, "source"), output = child(run, "generated"), modData = child(run, "mod-data");
            ensureDirectory(source); ensureDirectory(output); ensureDirectory(modData); checkCancelled();
            guard = OwnedRunGuard.capture(layout, root, run, source, output, modData);
            Map<String, Path> roots = new LinkedHashMap<>(); List<PatchworkSelfTestCase> fixtureCases = new ArrayList<>();
            for (PatchworkSelfTestCase original : pack.cases()) {
                checkCancelled(); guard.requireStable(); PatchworkSelfTestCase fixture = original.forRun(run.getFileName().toString()); fixtureCases.add(fixture);
                safeWrite(source, fixture.sourceTargetPath(), fixture.sourceTargetJson(), guard);
                safeWrite(source, fixture.patchDefinitionPath(), fixture.patchDefinitionJson(), guard);
                for (Map.Entry<String, String> asset : fixture.fixtureAssets().entrySet()) safeWrite(source, asset.getKey(), asset.getValue(), guard);
                if (fixture.registeredModId() != null) {
                    Path data = child(modData, fixture.registeredModId().replace(':', '_'));
                    guard.requireStable(); ensureDirectory(data); roots.putIfAbsent(fixture.registeredModId(), data);
                    for (Map.Entry<String, String> document : fixture.modDataDocuments().entrySet()) safeWrite(data, document.getKey(), document.getValue(), guard);
                }
            }
            checkpoint(Phase.AFTER_FIXTURES, run); checkCancelled(); guard.requireStable(); verifyOwnedTree(source, guard); verifyOwnedTree(modData, guard);
            ConditionSourceResolver resolver = new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(roots), new ConditionDocumentCache());
            checkpoint(Phase.BEFORE_GENERATION, run); checkCancelled();
            PatchGenerationService.GenerationPlan plan = new PatchGenerationService().generate(new PatchGenerationService.GenerationRequest(
                    List.of(PatchSource.directory("Patchwork:SelfTest", 0, source)), Set.copyOf(roots.keySet()), Map.of(), "self-test", resolver));
            checkCancelled();
            if (!plan.status().scanFailures().isEmpty() || !plan.status().rejectedTargets().isEmpty()) throw new IOException("Isolated generation rejected a fixture.");
            Map<String, byte[]> entries = new LinkedHashMap<>();
            for (var entry : plan.entries()) { checkCancelled(); guard.requireStable(); safeWrite(output, entry.target(), new String(entry.bytes(), StandardCharsets.UTF_8), guard); entries.put(entry.target(), entry.bytes()); generated.add(entry.target()); }
            for (PatchworkSelfTestCase fixture : fixtureCases) {
                checkCancelled(); byte[] bytes = entries.get(fixture.expectedGeneratedTarget());
                List<PatchworkSelfTestResult.CheckOutcome> checks = new ArrayList<>(); boolean pass = bytes != null;
                if (bytes != null) for (Map.Entry<String, String> expected : fixture.expectedPointers().entrySet()) { checkCancelled(); boolean match = Objects.equals(pointer(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)), expected.getKey()), JsonParser.parseString(expected.getValue())); checks.add(new PatchworkSelfTestResult.CheckOutcome(expected.getKey(), match)); pass &= match; }
                cases.add(new PatchworkSelfTestResult.CaseOutcome(fixture.expectedGeneratedTarget(), bytes != null, pass, checks, pass ? "" : "Generated output did not match fixture expectations."));
                if (!pass) throw new IOException("Isolated fixture expectation failed.");
            }
            checkpoint(Phase.BEFORE_RELOAD, run); checkCancelled(); reload = reloadHandle == null ? PatchworkSelfTestReloadHandle.ReloadOutcome.RESTART_REQUIRED : Objects.requireNonNull(reloadHandle.reloadIsolated(new PatchworkSelfTestReloadHandle.IsolatedGeneration(output, generated)));
            checkCancelled(); generation = PatchworkSelfTestResult.GenerationOutcome.GENERATED;
            completed = reload == PatchworkSelfTestReloadHandle.ReloadOutcome.HOT_RELOADED
                    || reload == PatchworkSelfTestReloadHandle.ReloadOutcome.ADAPTER_RELOADED
                    || reload == PatchworkSelfTestReloadHandle.ReloadOutcome.REMOVED
                    || reload == PatchworkSelfTestReloadHandle.ReloadOutcome.RESTART_REQUIRED;
            outcomes.add(completed ? "generated" : "reload-degraded"); outcomes.add("reload:" + reload.name().toLowerCase());
            if (!completed) diagnostic = "Self-test reload was not verified.";
        } catch (Cancelled failure) { wasCancelled = true; generation = PatchworkSelfTestResult.GenerationOutcome.CANCELLED; diagnostic = "Self-test cancelled."; outcomes.add("cancelled"); }
        catch (Exception failure) { diagnostic = "Self-test failed: " + safe(failure); outcomes.add("failed"); }
        finally {
            attempted = true;
            try { ensureRun(root, run); if (guard == null) throw new IOException("Self-test run ownership was not established."); guard.requireStableForCleanup(); deleteRun(root, run, guard); cleaned = !Files.exists(run, LinkOption.NOFOLLOW_LINKS); }
            catch (Exception failure) { diagnostic = diagnostic.isBlank() ? "Cleanup failed: " + safe(failure) : diagnostic + " Cleanup failed: " + safe(failure); }
        }
        return new PatchworkSelfTestResult(run, started, attempted, cleaned, outcomes, diagnostic, completed, wasCancelled, generation, reload, generated, cases);
    }

    private void ensureDirectory(Path path) throws IOException { safeExistingParent(path); if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { requireDirectory(path); return; } Files.createDirectory(path); requireDirectory(path); }
    private void safeWrite(Path root, String relative, String content, OwnedRunGuard guard) throws IOException {
        Path file = child(root, relative); guard.requireStable(); ensureParents(root, file.getParent()); guard.requireStable(); safeExistingParent(file);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Fixture attempted to overwrite an existing path.");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (SeekableByteChannel channel = Files.newByteChannel(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); if (channel instanceof FileChannel fileChannel) fileChannel.force(true);
        }
        guard.requireStable(); requireRegular(file);
    }
    private void ensureParents(Path root, Path directory) throws IOException { Path current = root; for (Path segment : root.relativize(directory)) { current = current.resolve(segment); ensureDirectory(current); } }
    private void verifyOwnedTree(Path root, OwnedRunGuard guard) throws IOException { guard.requireStable(); try (var paths = Files.walk(root)) { for (Path path : paths.toList()) { guard.requireStable(); if (Files.isSymbolicLink(path)) throw new IOException("Self-test input contains a link."); BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); if (attributes.isOther() || (!attributes.isDirectory() && !attributes.isRegularFile())) throw new IOException("Self-test input contains an unsafe file."); if (!path.equals(root)) safeExistingParent(path); } } guard.requireStable(); }
    private Path child(Path root, String relative) throws IOException { Path child = root.resolve(relative).normalize(); if (!child.startsWith(root) || child.equals(root)) throw new IOException("Self-test path escapes its root."); return child; }
    private void ensureRun(Path root, Path run) throws IOException { layout.requireSafeExistingComponents(root); if (!run.getParent().equals(root) || !UUID.fromString(run.getFileName().toString()).toString().equals(run.getFileName().toString())) throw new IOException("Self-test cleanup target is not an exact run child."); }
    private void safeExistingParent(Path path) throws IOException { if (!layout.isOwned(path)) throw new IOException("Self-test path escapes Patchwork data."); Path parent = path.getParent(); if (parent == null) throw new IOException("Self-test path has no parent."); layout.requireSafeExistingComponents(parent); }
    private static void requireDirectory(Path path) throws IOException { BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); if (Files.isSymbolicLink(path) || a.isOther() || !a.isDirectory()) throw new IOException("Unsafe self-test directory."); }
    private static void requireRegular(Path path) throws IOException { BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); if (Files.isSymbolicLink(path) || a.isOther() || !a.isRegularFile()) throw new IOException("Unsafe self-test file."); }
    private void deleteRun(Path root, Path run, OwnedRunGuard guard) throws IOException { ensureRun(root, run); if (!Files.exists(run, LinkOption.NOFOLLOW_LINKS)) return; requireDirectory(run); try (var stream = Files.walk(run)) { for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) { guard.requireStableForCleanup(); if (!path.normalize().startsWith(run)) throw new IOException("Cleanup escaped run."); if (Files.isSymbolicLink(path)) throw new IOException("Refusing linked self-test cleanup."); BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); if (attributes.isOther() || (!attributes.isDirectory() && !attributes.isRegularFile())) throw new IOException("Refusing unsafe self-test cleanup."); if (!path.equals(run)) safeExistingParent(path); files.delete(path); } } }
    /** Retains the owned run's component identities so mutable names cannot be reused after setup. */
    private static final class OwnedRunGuard {
        private final GeneratedPackLayout layout; private final Map<Path, Identity> identities; private final Map<Path, Identity> cleanupIdentities;
        private OwnedRunGuard(GeneratedPackLayout layout, Map<Path, Identity> identities, Map<Path, Identity> cleanupIdentities) { this.layout = layout; this.identities = identities; this.cleanupIdentities = cleanupIdentities; }
        static OwnedRunGuard capture(GeneratedPackLayout layout, Path selfTest, Path run, Path source, Path generated, Path modData) throws IOException {
            Map<Path, Identity> identities = new LinkedHashMap<>();
            for (Path path : List.of(layout.dataRoot(), selfTest, run, source, generated, modData)) identities.put(path, Identity.capture(path));
            Map<Path, Identity> retained = Map.copyOf(identities);
            return new OwnedRunGuard(layout, retained, Map.of(layout.dataRoot(), retained.get(layout.dataRoot()), selfTest, retained.get(selfTest), run, retained.get(run)));
        }
        void requireStable() throws IOException { for (Map.Entry<Path, Identity> entry : identities.entrySet()) { layout.requireSafeExistingComponents(entry.getKey()); if (!entry.getValue().equals(Identity.capture(entry.getKey()))) throw new IOException("Self-test owned component was replaced."); } }
        void requireStableForCleanup() throws IOException { for (Map.Entry<Path, Identity> entry : cleanupIdentities.entrySet()) { layout.requireSafeExistingComponents(entry.getKey()); if (!entry.getValue().equals(Identity.capture(entry.getKey()))) throw new IOException("Self-test owned component was replaced."); } }
        private record Identity(String fileKey, long creation, long modified, long size, boolean directory) {
            static Identity capture(Path path) throws IOException { BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return new Identity(String.valueOf(a.fileKey()), a.creationTime().toMillis(), a.isDirectory() ? 0 : a.lastModifiedTime().toMillis(), a.isDirectory() ? 0 : a.size(), a.isDirectory()); }
        }
    }
    private void checkCancelled() { if (cancelled.get()) throw new Cancelled(); }
    private void checkpoint(Phase phase, Path run) { phases.reached(phase, run); }
    private static JsonElement pointer(JsonElement root, String value) { if (!value.startsWith("/")) throw new IllegalArgumentException("Expected pointer must start with '/'."); JsonElement current = root; for (String raw : value.substring(1).split("/", -1)) { String part = raw.replace("~1", "/").replace("~0", "~"); if (current.isJsonObject()) current = current.getAsJsonObject().get(part); else if (current.isJsonArray()) current = current.getAsJsonArray().get(Integer.parseInt(part)); else return null; if (current == null) return null; } return current; }
    private static String safe(Exception failure) { return failure.getClass().getSimpleName(); }
    @FunctionalInterface interface FileOperations { void delete(Path path) throws IOException; }
    @FunctionalInterface interface PhaseHook { void reached(Phase phase, Path run); }
    enum Phase { AFTER_FIXTURES, BEFORE_GENERATION, BEFORE_RELOAD }
    private static final class Cancelled extends RuntimeException { }
}
