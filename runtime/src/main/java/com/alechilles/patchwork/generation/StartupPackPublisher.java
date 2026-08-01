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

/** Stages, validates, activates, and only then registers a generated Hytale asset pack. */
public final class StartupPackPublisher {
    public static final String PACK_ID = "Alechilles:Patchwork_GeneratedPatches";
    private final GeneratedPackLayout layout;
    private final PackRegistrar registrar;
    private final MoveStrategy moves;
    private final StagingVerifier verifier;
    private final FileDeletion deletion;

    public StartupPackPublisher(GeneratedPackLayout layout, PackRegistrar registrar) {
        this(layout, registrar, new FileMoveStrategy(), StartupPackPublisher::verifyDefault, Files::delete);
    }

    StartupPackPublisher(GeneratedPackLayout layout, PackRegistrar registrar, MoveStrategy moves, StagingVerifier verifier) {
        this(layout, registrar, moves, verifier, Files::delete);
    }

    StartupPackPublisher(GeneratedPackLayout layout, PackRegistrar registrar, MoveStrategy moves, StagingVerifier verifier, FileDeletion deletion) {
        this.layout = Objects.requireNonNull(layout); this.registrar = Objects.requireNonNull(registrar);
        this.moves = Objects.requireNonNull(moves); this.verifier = Objects.requireNonNull(verifier); this.deletion = Objects.requireNonNull(deletion);
    }

    /** Fails closed: no result is current unless staging was validated, activated, and registered. */
    public Publication publish(PatchGenerationService.GenerationPlan plan) {
        Objects.requireNonNull(plan, "plan");
        String token = UUID.randomUUID().toString();
        Path staging = layout.stagingRoot(token), live = layout.generatedRoot(), prior = layout.quarantineRoot(token);
        List<Path> evidence = new ArrayList<>();
        try {
            layout.createSafeDataRoot();
            cleanupInterruptedStaging();
            createStaging(staging);
            writePlan(staging, plan);
            verifier.verify(staging, plan);
            if (Files.exists(live, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                layout.requireSafeExistingComponents(live);
                createDiagnostics();
                move(live, prior);
                evidence.add(prior);
            }
            move(staging, live);
            layout.requireSafeExistingComponents(live);
            registrar.register(PACK_ID);
            return new Publication(true, "", live, List.copyOf(evidence), List.of());
        } catch (Exception failure) {
            List<Path> residual = retainFailure(staging, live, evidence);
            if (failure instanceof OwnedCleanupFailure cleanupFailure) residual.add(cleanupFailure.root());
            return new Publication(false, message(failure), null, ordered(evidence), ordered(residual));
        }
    }

    private void createStaging(Path staging) throws IOException {
        layout.requireSafeExistingComponents(staging.getParent());
        if (!layout.isOwnedStagingChild(staging)) throw new IOException("Staging path is not an owned direct child.");
        Files.createDirectory(staging);
        layout.requireSafeExistingComponents(staging);
    }

    private void writePlan(Path staging, PatchGenerationService.GenerationPlan plan) throws IOException {
        for (GeneratedPackManifest.Entry entry : plan.entries()) write(staging, entry.target(), entry.bytes());
        write(staging, "manifest.json", hytaleManifest(plan.sourcePackIds()));
        write(staging, GeneratedPackManifest.FILE_NAME, plan.manifest().bytes());
    }

    private void write(Path staging, String relative, byte[] bytes) throws IOException {
        Path target = staging.resolve(relative).normalize();
        if (!target.startsWith(staging)) throw new IOException("Generated target escapes staging root.");
        layout.requireSafeExistingComponents(staging);
        Files.createDirectories(target.getParent());
        layout.requireSafeExistingComponents(target.getParent());
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(bytes)); channel.force(true);
        }
    }

    private void cleanupInterruptedStaging() throws IOException {
        layout.requireSafeExistingComponents(layout.dataRoot());
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
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                layout.requireSafeExistingLeaf(path);
                deletion.delete(path);
            }
        }
    }

    private List<Path> retainFailure(Path staging, Path live, List<Path> evidence) {
        List<Path> residual = new ArrayList<>();
        retainOne(staging, true, evidence, residual);
        retainOne(live, true, evidence, residual);
        return residual;
    }

    private void retainOne(Path root, boolean failedNew, List<Path> evidence, List<Path> residual) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try {
            layout.requireSafeExistingComponents(root); createDiagnostics();
            Path retained = failedNew ? layout.failedNewRoot(UUID.randomUUID().toString()) : layout.quarantineRoot(UUID.randomUUID().toString());
            move(root, retained); evidence.add(retained);
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
        layout.requireSafeExistingComponents(from); layout.requireSafeExistingComponents(to.getParent());
        try { moves.atomicMove(from, to); }
        catch (AtomicMoveNotSupportedException unsupported) { moves.nonAtomicMove(from, to); }
    }


    private static void verifyDefault(Path staging, PatchGenerationService.GenerationPlan plan) throws IOException {
        for (GeneratedPackManifest.Entry entry : plan.entries()) {
            Path file = staging.resolve(entry.target()).normalize();
            if (!file.startsWith(staging) || !java.util.Arrays.equals(entry.bytes(), Files.readAllBytes(file))) throw new IOException("Staged target integrity mismatch.");
        }
        JsonObject manifest = parseObject(Files.readString(staging.resolve("manifest.json")));
        if (!PACK_ID.equals(manifest.get("Group").getAsString() + ":" + manifest.get("Name").getAsString())) throw new IOException("Generated Hytale manifest ID is invalid.");
        if (!"1.0.0".equals(manifest.get("Version").getAsString()) || !"*".equals(manifest.get("ServerVersion").getAsString())) throw new IOException("Generated Hytale manifest version is invalid.");
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
        for (String id : sourcePackIds.stream().filter(id -> !PACK_ID.equals(id)).sorted().toList()) dependencies.addProperty(id, "*");
        root.add("Dependencies", dependencies);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws IOException {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception unavailable) { throw new IOException("SHA-256 is unavailable.", unavailable); }
    }
    private static String message(Exception failure) { return failure.getMessage() == null ? "Publication failed." : failure.getMessage(); }
    private static List<Path> ordered(List<Path> paths) { return paths.stream().distinct().sorted(Comparator.comparing(Path::toString)).toList(); }

    @FunctionalInterface public interface PackRegistrar { void register(String packId) throws Exception; }
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
    public record Publication(boolean published, String diagnostic, Path activeRoot, List<Path> recoveryEvidence, List<Path> residualEvidence) {
        public Publication { recoveryEvidence = List.copyOf(recoveryEvidence); residualEvidence = List.copyOf(residualEvidence); }
    }
}
