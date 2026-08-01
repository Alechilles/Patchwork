package com.alechilles.patchwork.generation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.hypixel.hytale.common.plugin.PluginIdentifier;

/** Stages, validates, activates, and only then registers a generated Hytale asset pack. */
public final class StartupPackPublisher {
    public static final String PACK_ID = "Alechilles:Patchwork_GeneratedPatches";
    private final GeneratedPackLayout layout;
    private final PackRegistrar registrar;
    private final MoveStrategy moves;
    private final StagingVerifier verifier;
    private final FileDeletion deletion;
    private final OwnedPathAccess access;

    public StartupPackPublisher(GeneratedPackLayout layout, PackRegistrar registrar) {
        this(layout, registrar, new FileMoveStrategy(), StartupPackPublisher::verifyDefault, Files::delete, new OwnedPathAccess(layout));
    }

    StartupPackPublisher(GeneratedPackLayout layout, PackRegistrar registrar, MoveStrategy moves, StagingVerifier verifier) {
        this(layout, registrar, moves, verifier, Files::delete, new OwnedPathAccess(layout));
    }

    StartupPackPublisher(GeneratedPackLayout layout, PackRegistrar registrar, MoveStrategy moves, StagingVerifier verifier, FileDeletion deletion) { this(layout, registrar, moves, verifier, deletion, new OwnedPathAccess(layout)); }
    StartupPackPublisher(GeneratedPackLayout layout, PackRegistrar registrar, MoveStrategy moves, StagingVerifier verifier, FileDeletion deletion, OwnedPathAccess access) {
        this.layout = Objects.requireNonNull(layout); this.registrar = Objects.requireNonNull(registrar);
        this.moves = Objects.requireNonNull(moves); this.verifier = Objects.requireNonNull(verifier); this.deletion = Objects.requireNonNull(deletion); this.access = Objects.requireNonNull(access);
    }

    /** Fails closed: no result is current unless staging was validated, activated, and registered. */
    public Publication publish(PatchGenerationService.GenerationPlan plan) {
        Objects.requireNonNull(plan, "plan");
        String token = UUID.randomUUID().toString();
        Path staging = layout.stagingRoot(token), live = layout.generatedRoot(), prior = layout.quarantineRoot(token);
        List<Path> evidence = new ArrayList<>();
        List<MoveIntent> intents = new ArrayList<>();
        boolean priorPresent = false;
        boolean activationConfirmed = false;
        RegistrationAttempt registration = null;
        try {
            priorPresent = Files.exists(live, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            layout.createSafeDataRoot();
            cleanupInterruptedStaging();
            createStaging(staging);
            writePlan(staging, plan);
            verifier.verify(staging, plan);
            if (Files.exists(live, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                layout.requireSafeExistingComponents(live);
                createDiagnostics();
                move(live, prior, "PRIOR", "QUARANTINE", intents);
                evidence.add(prior);
            }
            move(staging, live, "FAILED_NEW", "ACTIVATION", intents);
            activationConfirmed = true;
            layout.requireSafeExistingComponents(live);
            registration = registrar.prepare(PACK_ID);
            registration.commit();
            return new Publication(true, "", live, List.copyOf(evidence), List.of(), false);
        } catch (Exception failure) {
            boolean unresolved = false;
            if (registration != null) {
                try { registration.rollback(); }
                catch (Exception rollbackFailure) { unresolved = true; }
            }
            if (unresolved) return new Publication(false, message(failure), null, ordered(evidence), List.of(live), true);
            // A move may have committed before its provider reports failure; reconcile both named endpoints.
            if (Files.exists(prior, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !evidence.contains(prior)) evidence.add(prior);
            if (!activationConfirmed && intents.stream().anyMatch(intent -> "ACTIVATION".equals(intent.phase())
                    && Files.exists(intent.destination(), java.nio.file.LinkOption.NOFOLLOW_LINKS))) activationConfirmed = true;
            List<Path> residual = reconcile(intents, evidence);
            residual.addAll(retainFailure(staging, live, evidence, priorPresent, activationConfirmed, intents));
            residual.addAll(reconcile(intents, evidence));
            if (failure instanceof OwnedCleanupFailure cleanupFailure) residual.add(cleanupFailure.root());
            return new Publication(false, message(failure), null, existing(ordered(evidence)), existing(ordered(residual)), false);
        }
    }

    private void createStaging(Path staging) throws IOException {
        access.guard(staging.getParent());
        if (!layout.isOwnedStagingChild(staging)) throw new IOException("Staging path is not an owned direct child.");
        Files.createDirectory(staging);
        OwnedPathAccess.Identity createdIdentity = access.captureIdentity(staging);
        access.afterCreation(staging);
        access.requireSameIdentity(staging, createdIdentity);
    }

    private void writePlan(Path staging, PatchGenerationService.GenerationPlan plan) throws IOException {
        hytaleManifest(plan.sourcePackIds()); // validates every dependency before writing any staged bytes
        for (GeneratedPackManifest.Entry entry : plan.entries()) write(staging, entry.target(), entry.bytes());
        write(staging, "manifest.json", hytaleManifest(plan.sourcePackIds()));
        write(staging, GeneratedPackManifest.FILE_NAME, plan.manifest().bytes());
    }

    private void write(Path staging, String relative, byte[] bytes) throws IOException {
        Path target = staging.resolve(relative).normalize();
        if (!target.startsWith(staging)) throw new IOException("Generated target escapes staging root.");
        access.guard(staging);
        Files.createDirectories(target.getParent());
        access.guard(target.getParent());
        if (SecureOwnedDirectories.writeOwnedFile(staging, relative, bytes)) return;
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private void cleanupInterruptedStaging() throws IOException {
        access.guard(layout.dataRoot());
        try (var children = Files.list(layout.dataRoot())) {
            for (Path child : children.toList()) if (layout.isOwnedStagingChild(child)) {
                try { deleteOwnedStaging(child); }
                catch (IOException failure) { throw new OwnedCleanupFailure(child, failure); }
            }
        }
    }

    private void deleteOwnedStaging(Path root) throws IOException {
        if (!layout.isOwnedStagingChild(root)) return;
        layout.requireSafeExistingComponents(root);
        if (SecureOwnedDirectories.deleteOwnedTree(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                access.guard(path);
                deletion.delete(path);
            }
        }
    }

    private List<Path> retainFailure(Path staging, Path live, List<Path> evidence, boolean priorPresent, boolean activationConfirmed, List<MoveIntent> intents) {
        List<Path> residual = new ArrayList<>();
        if (!activationConfirmed && priorPresent) {
            retainOne(live, false, evidence, residual, intents);
            retainOne(staging, true, evidence, residual, intents);
        } else {
            retainOne(staging, true, evidence, residual, intents);
            retainOne(live, true, evidence, residual, intents);
        }
        return residual;
    }

    private void retainOne(Path root, boolean failedNew, List<Path> evidence, List<Path> residual, List<MoveIntent> intents) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try {
            layout.requireSafeExistingComponents(root); createDiagnostics();
            Path retained = failedNew ? layout.failedNewRoot(UUID.randomUUID().toString()) : layout.quarantineRoot(UUID.randomUUID().toString());
            move(root, retained, failedNew ? "FAILED_NEW" : "PRIOR", "RECOVERY", intents); evidence.add(retained);
        } catch (Exception retentionFailure) {
            // A failed recovery move must remain visible to callers, never masquerade as active content.
            if (Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) residual.add(root);
        }
    }

    private void createDiagnostics() throws IOException {
        Files.createDirectories(layout.diagnosticsRoot());
        layout.requireSafeExistingComponents(layout.diagnosticsRoot());
    }

    private void move(Path from, Path to) throws IOException {
        access.guard(from); access.guard(to.getParent());
        if (SecureOwnedDirectories.moveOwnedDirectory(from, to)) return;
        try { moves.atomicMove(from, to); }
        catch (AtomicMoveNotSupportedException unsupported) { moves.nonAtomicMove(from, to); }
    }
    private void move(Path from, Path to, String kind, String phase, List<MoveIntent> intents) throws IOException {
        MoveIntent intent = new MoveIntent(from, to, kind, phase);
        intents.add(intent);
        move(from, to);
        intents.remove(intent);
    }
    private static List<Path> reconcile(List<MoveIntent> intents, List<Path> evidence) {
        List<Path> residual = new ArrayList<>();
        for (MoveIntent intent : intents) {
            if (Files.exists(intent.destination(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && !evidence.contains(intent.destination())) evidence.add(intent.destination());
            if (Files.exists(intent.source(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) residual.add(intent.source());
        }
        return residual;
    }

    /** Immutable move bookkeeping identity retained by publication recovery before a filesystem mutation begins. */
    private record MoveIntent(Path source, Path destination, String evidenceKind, String phase) { }


    static void verifyDefault(Path staging, PatchGenerationService.GenerationPlan plan) throws IOException {
        for (GeneratedPackManifest.Entry entry : plan.entries()) {
            Path file = staging.resolve(entry.target()).normalize();
            if (!file.startsWith(staging) || !java.util.Arrays.equals(entry.bytes(), Files.readAllBytes(file))) throw new IOException("Staged target integrity mismatch.");
        }
        if (!java.util.Arrays.equals(hytaleManifest(plan.sourcePackIds()), Files.readAllBytes(staging.resolve("manifest.json")))) throw new IOException("Generated Hytale manifest does not match the expected dependency contract.");
        if (!java.util.Arrays.equals(plan.manifest().bytes(), Files.readAllBytes(staging.resolve(GeneratedPackManifest.FILE_NAME)))) throw new IOException("Generated integrity manifest does not exactly match the publication plan.");
        JsonObject inventory = parseObject(Files.readString(staging.resolve(GeneratedPackManifest.FILE_NAME)));
        JsonArray files = inventory.getAsJsonArray("Files");
        if (files == null || files.size() != plan.entries().size()) throw new IOException("Generated integrity manifest is invalid.");
        for (JsonElement raw : files) {
            JsonObject file = raw.getAsJsonObject(); byte[] bytes = Files.readAllBytes(staging.resolve(file.get("Target").getAsString()));
            if (bytes.length != file.get("Length").getAsLong() || !sha256(bytes).equals(file.get("Sha256").getAsString())) throw new IOException("Generated integrity manifest hash mismatch.");
        }
    }

    private static JsonObject parseObject(String json) throws IOException {
        try { JsonElement parsed = JsonParser.parseString(json); if (!parsed.isJsonObject()) throw new IOException("Manifest is not an object."); return parsed.getAsJsonObject(); }
        catch (RuntimeException bad) { throw new IOException("Manifest is malformed.", bad); }
    }

    static byte[] hytaleManifest(List<String> sourcePackIds) {
        JsonObject root = new JsonObject(); root.addProperty("Group", "Alechilles"); root.addProperty("Name", "Patchwork_GeneratedPatches");
        root.addProperty("Version", "1.0.0"); root.addProperty("Description", "Generated Patchwork asset pack.");
        root.add("Authors", new JsonArray()); root.addProperty("ServerVersion", "*");
        JsonObject dependencies = new JsonObject();
        for (String id : sourcePackIds.stream().filter(id -> !PACK_ID.equals(id)).sorted().toList()) {
            try { PluginIdentifier.fromString(id); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid generated-pack dependency ID.", invalid); }
            dependencies.addProperty(id, "*");
        }
        root.add("Dependencies", dependencies);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws IOException {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception unavailable) { throw new IOException("SHA-256 is unavailable.", unavailable); }
    }
    private static String message(Exception failure) { return failure.getMessage() == null ? "Publication failed." : failure.getMessage(); }
    private static List<Path> ordered(List<Path> paths) { return paths.stream().distinct().sorted(Comparator.comparing(Path::toString)).toList(); }
    private static List<Path> existing(List<Path> paths) { return paths.stream().filter(path -> Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList(); }

    @FunctionalInterface public interface PackRegistrar {
        void register(String packId) throws Exception;
        /** Compatibility adapter: a failed one-way registration is unresolved because it cannot prove rollback. */
        default RegistrationAttempt prepare(String packId) { return new RegistrationAttempt() { public void commit() throws Exception { register(packId); } public void rollback() throws Exception { throw new IOException("One-way registrar cannot prove rollback."); } }; }
    }
    public interface RegistrationAttempt { void commit() throws Exception; void rollback() throws Exception; }
    @FunctionalInterface interface FileDeletion { void delete(Path path) throws IOException; }
    private static final class OwnedCleanupFailure extends IOException {
        private final Path root;
        private OwnedCleanupFailure(Path root, IOException cause) { super("Unable to remove owned interrupted staging: " + root, cause); this.root = root; }
        private Path root() { return root; }
    }
    interface MoveStrategy { void atomicMove(Path from, Path to) throws IOException; void nonAtomicMove(Path from, Path to) throws IOException; }
    static final class FileMoveStrategy implements MoveStrategy {
        @Override public void atomicMove(Path from, Path to) throws IOException { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
        @Override public void nonAtomicMove(Path from, Path to) throws IOException { Files.move(from, to); }
    }
    @FunctionalInterface interface StagingVerifier { void verify(Path staging, PatchGenerationService.GenerationPlan plan) throws IOException; }
    /** Publication result with deterministic, non-current recovery evidence. */
    public record Publication(boolean published, String diagnostic, Path activeRoot, List<Path> recoveryEvidence, List<Path> residualEvidence, boolean registrationUnresolved) {
        public Publication { recoveryEvidence = List.copyOf(recoveryEvidence); residualEvidence = List.copyOf(residualEvidence); }
    }
}
