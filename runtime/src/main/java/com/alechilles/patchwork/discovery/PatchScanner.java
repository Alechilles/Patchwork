package com.alechilles.patchwork.discovery;

import com.alechilles.patchwork.engine.PatchDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Deterministically discovers filesystem patch definitions without Hytale runtime dependencies. */
public final class PatchScanner {
    /** Generated output pack that must never be treated as a patch input. */
    public static final String GENERATED_PACK_ID = "Alechilles:Patchwork_GeneratedPatches";

    /** Scans active root paths across sources and returns immutable definitions and diagnostics. */
    public ScanResult scan(List<PatchSource> sources, Set<String> installedPluginIds) {
        List<PatchDefinition> definitions = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Map<DuplicateKey, PatchRoot> accepted = new HashMap<>();
        List<PatchSource> orderedSources = sources.stream().filter(source -> !GENERATED_PACK_ID.equals(source.sourcePackId()))
                .sorted(Comparator.comparingInt(PatchSource::sourcePackLoadOrder).thenComparing(PatchSource::sourcePackId)).toList();
        for (PatchRoot root : PatchRoot.activeRoots(Set.copyOf(installedPluginIds))) {
            for (PatchSource source : orderedSources) {
                scanRoot(source, root, definitions, accepted, skipped, failures);
            }
        }
        return new ScanResult(definitions, skipped, failures);
    }

    private static void scanRoot(PatchSource source, PatchRoot root, List<PatchDefinition> definitions,
                                 Map<DuplicateKey, PatchRoot> accepted, List<String> skipped, List<String> failures) {
        try {
            for (SourceFile file : sourceFiles(source, root.path())) {
                processFile(source, root, file, definitions, accepted, skipped, failures);
            }
        } catch (Exception exception) {
            failures.add("Failed to scan " + source.sourcePackId() + ":" + root.path() + ": " + exception.getMessage());
        }
    }

    private static void processFile(PatchSource source, PatchRoot root, SourceFile file, List<PatchDefinition> definitions,
                                    Map<DuplicateKey, PatchRoot> accepted, List<String> skipped, List<String> failures) {
        try {
            JsonElement element = JsonParser.parseString(new String(file.bytes(), StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Patch file must contain a JSON object.");
            }
            List<PatchDefinition> parsed = PatchDefinition.parseAll(element.getAsJsonObject(), source.sourcePackId(), file.assetPath(), source.sourcePackLoadOrder())
                    .stream().sorted(Comparator.comparing(PatchDefinition::target)).toList();
            validateTargets(parsed);
            List<PatchDefinition> enabled = parsed.stream().filter(PatchDefinition::enabled).toList();
            for (PatchDefinition definition : parsed) {
                if (!definition.enabled()) {
                    skipped.add(definition.id() + " disabled for " + definition.target());
                }
            }
            if (containsSameRootDuplicate(root, enabled, accepted)) {
                failures.add("Duplicate patch key in " + source.sourcePackId() + ":" + file.assetPath());
                return;
            }
            if (root == PatchRoot.NEUTRAL) {
                removeShadowedLegacy(enabled, definitions, accepted);
            }
            for (PatchDefinition definition : enabled) {
                accepted.put(key(definition), root);
                definitions.add(definition);
            }
        } catch (Exception exception) {
            failures.add("Failed to parse " + source.sourcePackId() + ":" + file.assetPath() + ": " + exception.getMessage());
        }
    }

    private static void validateTargets(List<PatchDefinition> definitions) {
        for (PatchDefinition definition : definitions) {
            if (!normalizeAssetPath(definition.target()).equals(definition.target())) {
                throw new IllegalArgumentException("Unsafe target path: " + definition.target());
            }
        }
    }

    private static boolean containsSameRootDuplicate(PatchRoot root, List<PatchDefinition> definitions, Map<DuplicateKey, PatchRoot> accepted) {
        Set<DuplicateKey> local = new HashSet<>();
        for (PatchDefinition definition : definitions) {
            DuplicateKey key = key(definition);
            if (!local.add(key) || accepted.get(key) == root) {
                return true;
            }
        }
        return false;
    }

    private static void removeShadowedLegacy(List<PatchDefinition> neutral, List<PatchDefinition> definitions,
                                             Map<DuplicateKey, PatchRoot> accepted) {
        Set<DuplicateKey> shadows = neutral.stream().map(PatchScanner::key).collect(java.util.stream.Collectors.toSet());
        if (shadows.isEmpty()) return;
        definitions.removeIf(definition -> shadows.contains(key(definition)));
        accepted.clear();
        for (PatchDefinition definition : definitions) accepted.put(key(definition), PatchRoot.LEGACY);
    }

    private static DuplicateKey key(PatchDefinition definition) {
        return new DuplicateKey(definition.sourcePack(), definition.id(), definition.target());
    }

    private static List<SourceFile> sourceFiles(PatchSource source, String root) throws IOException {
        return source.kind() == PatchSource.Kind.DIRECTORY ? directoryFiles(source.backingPath(), root) : archiveFiles(source.backingPath(), root);
    }

    private static List<SourceFile> directoryFiles(Path packRoot, String root) throws IOException {
        Path normalizedRoot = packRoot.resolve(root).normalize();
        if (!normalizedRoot.startsWith(packRoot) || !Files.isDirectory(normalizedRoot)) return List.of();
        Path realPackRoot = packRoot.toRealPath();
        try (var files = Files.walk(normalizedRoot)) {
            return files.filter(Files::isRegularFile).filter(file -> isInside(file, realPackRoot))
                    .map(file -> new SourceFile(normalizeAssetPath(packRoot.relativize(file).toString()), readFile(file)))
                    .filter(file -> file.assetPath().endsWith(".json")).sorted(Comparator.comparing(SourceFile::assetPath)).toList();
        }
    }

    private static boolean isInside(Path file, Path realPackRoot) {
        try { return file.toRealPath().startsWith(realPackRoot); } catch (IOException exception) { return false; }
    }

    private static byte[] readFile(Path file) {
        try { return Files.readAllBytes(file); } catch (IOException exception) { throw new FileReadException(exception); }
    }

    private static List<SourceFile> archiveFiles(Path archive, String root) throws IOException {
        String prefix = root + "/";
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<SourceFile> result = new ArrayList<>();
            for (ZipEntry entry : zip.stream().filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix) && entry.getName().endsWith(".json")).sorted(Comparator.comparing(ZipEntry::getName)).toList()) {
                try (var input = zip.getInputStream(entry)) {
                    result.add(new SourceFile(normalizeAssetPath(entry.getName()), input.readAllBytes()));
                }
            }
            return List.copyOf(result);
        }
    }

    static String normalizeAssetPath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Asset path must not be blank.");
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*") || normalized.contains("//")) throw new IllegalArgumentException("Unsafe asset path: " + path);
        String[] segments = normalized.split("/");
        for (String segment : segments) if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("Unsafe asset path: " + path);
        return normalized;
    }

    /** Immutable discovery output, including non-fatal skips and deterministic failures. */
    public record ScanResult(List<PatchDefinition> definitions, List<String> skipped, List<String> failures) {
        public ScanResult { definitions = List.copyOf(definitions); skipped = List.copyOf(skipped); failures = List.copyOf(failures); }
    }

    private record DuplicateKey(String sourcePackId, String patchId, String target) { }
    private record SourceFile(String assetPath, byte[] bytes) { }
    private static final class FileReadException extends RuntimeException { FileReadException(IOException cause) { super(cause); } }
}
