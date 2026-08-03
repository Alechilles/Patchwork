package com.alechilles.patchwork.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alechilles.patchwork.engine.PatchDefinition;
import com.alechilles.patchwork.format.PatchLanguage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests deterministic filesystem patch discovery and root precedence. */
final class PatchScannerTest {
    @TempDir Path tempDir;

    @Test
    void scansNeutralPatchesFromDirectoryAndArchivePacksInStableOrder() throws Exception {
        Path directory = tempDir.resolve("directory");
        write(directory, "Server/Patchwork/Patches/Z.json", patch("z", "Server/Z.json"));
        write(directory, "Server/Patchwork/Patches/A.json", patch("a", "Server/A.json"));
        Path archive = tempDir.resolve("archive.zip");
        writeArchive(archive, "Server/Patchwork/Patches/B.json", patch("b", "Server/B.json"));

        PatchScanner.ScanResult result = new PatchScanner().scan(List.of(
                PatchSource.archive("archive", 2, archive),
                PatchSource.directory("directory", 1, directory)), Set.of());

        assertEquals(List.of("a", "z", "b"), result.definitions().stream().map(definition -> definition.id()).toList());
        assertEquals(List.of(), result.failures());
    }

    @Test
    void ordersSourcePacksByUnsignedUtf8SourceId() throws Exception {
        String privateUsePack = new String(Character.toChars(0xE000)) + "-pack";
        String supplementaryPack = new String(Character.toChars(0x10000)) + "-pack";
        Path privateUse = tempDir.resolve("private-use");
        Path supplementary = tempDir.resolve("supplementary");
        write(privateUse, "Server/Patchwork/Patches/private.json", patch("private", "Server/Private.json"));
        write(supplementary, "Server/Patchwork/Patches/supplementary.json", patch("supplementary", "Server/Supplementary.json"));

        PatchScanner.ScanResult result = new PatchScanner().scan(List.of(
                PatchSource.directory(supplementaryPack, 1, supplementary),
                PatchSource.directory(privateUsePack, 1, privateUse)), Set.of());

        assertEquals(List.of(privateUsePack, supplementaryPack), result.definitions().stream()
                .map(PatchDefinition::sourcePack).toList());
    }

    @Test
    void enablesLegacyRootOnlyForExactTameworkPluginId() throws Exception {
        Path pack = tempDir.resolve("pack");
        write(pack, "Server/Patchwork/Patches/Neutral.json", patch("neutral", "Server/Neutral.json"));
        write(pack, "Server/Tamework/Patches/Legacy.json", patch("legacy", "Server/Legacy.json"));
        PatchSource source = PatchSource.directory("pack", 1, pack);

        assertEquals(List.of("neutral"), new PatchScanner().scan(List.of(source), Set.of()).definitions().stream().map(definition -> definition.id()).toList());
        PatchScanner.ScanResult withTamework = new PatchScanner().scan(
                List.of(source), Set.of("Alechilles:Alec's Tamework!"));
        assertEquals(List.of("legacy", "neutral"), withTamework.definitions().stream()
                .map(definition -> definition.id()).toList());
        PatchDefinition legacy = withTamework.definitions().getFirst();
        assertEquals(PatchLanguage.LEGACY_V1, legacy.language());
        assertEquals(1, legacy.formatVersion());
    }

    @Test
    void neutralDefinitionShadowsMatchingLegacyDefinition() throws Exception {
        Path pack = tempDir.resolve("pack");
        write(pack, "Server/Tamework/Patches/Legacy.json", patch("shared", "Server/Target.json"));
        write(pack, "Server/Patchwork/Patches/Neutral.json", patch("shared", "Server/Target.json"));

        PatchScanner.ScanResult result = new PatchScanner().scan(List.of(PatchSource.directory("pack", 1, pack)), Set.of("Alechilles:Alec's Tamework!"));

        assertEquals(List.of("Server/Patchwork/Patches/Neutral.json"), result.definitions().stream().map(definition -> definition.sourcePath()).toList());
        assertEquals(List.of(), result.failures());
    }

    @Test
    void rejectsAllDefinitionsFromSecondDuplicateFileWithinRoot() throws Exception {
        Path pack = tempDir.resolve("pack");
        write(pack, "Server/Patchwork/Patches/A.json", patch("shared", "Server/Target.json"));
        write(pack, "Server/Patchwork/Patches/B.json", patch("shared", "Server/Target.json"));

        PatchScanner.ScanResult result = new PatchScanner().scan(List.of(PatchSource.directory("pack", 1, pack)), Set.of());

        assertEquals(List.of("Server/Patchwork/Patches/A.json"), result.definitions().stream().map(definition -> definition.sourcePath()).toList());
        assertEquals(1, result.failures().size());
        assertEquals("Duplicate patch key in pack:Server/Patchwork/Patches/B.json", result.failures().getFirst());
    }

    @Test
    void rejectsDuplicateNeutralFileAfterAnInterveningNeutralFileWithoutAcceptingItsOtherTarget() throws Exception {
        Path pack = tempDir.resolve("pack");
        write(pack, "Server/Patchwork/Patches/A.json", patch("shared", "Server/X.json"));
        write(pack, "Server/Patchwork/Patches/B.json", patch("other", "Server/Y.json"));
        write(pack, "Server/Patchwork/Patches/C.json", multiTargetPatch("shared", "Server/X.json", "Server/Z.json"));

        PatchScanner.ScanResult result = new PatchScanner().scan(List.of(PatchSource.directory("pack", 1, pack)), Set.of());

        assertEquals(List.of("Server/X.json", "Server/Y.json"), result.definitions().stream().map(definition -> definition.target()).toList());
        assertEquals(List.of("Duplicate patch key in pack:Server/Patchwork/Patches/C.json"), result.failures());
    }

    @Test
    void permitsMatchingDefinitionsFromDifferentPacksAndIgnoresGeneratedPack() throws Exception {
        Path one = tempDir.resolve("one");
        Path two = tempDir.resolve("two");
        Path generated = tempDir.resolve("generated");
        write(one, "Server/Patchwork/Patches/One.json", patch("shared", "Server/Target.json"));
        write(two, "Server/Patchwork/Patches/Two.json", patch("shared", "Server/Target.json"));
        write(generated, "Server/Patchwork/Patches/Generated.json", patch("generated", "Server/Generated.json"));

        PatchScanner.ScanResult result = new PatchScanner().scan(List.of(
                PatchSource.directory("one", 1, one),
                PatchSource.directory("two", 2, two),
                PatchSource.directory(PatchScanner.GENERATED_PACK_ID, 3, generated)), Set.of());

        assertEquals(List.of("shared", "shared"), result.definitions().stream().map(definition -> definition.id()).toList());
    }

    @Test
    void rejectsTraversalTargetsAndReturnsImmutableResultCollections() throws Exception {
        Path pack = tempDir.resolve("pack");
        write(pack, "Server/Patchwork/Patches/Bad.json", patch("bad", "../outside.json"));

        PatchScanner.ScanResult result = new PatchScanner().scan(List.of(PatchSource.directory("pack", 1, pack)), Set.of());

        assertEquals(1, result.failures().size());
        assertThrows(UnsupportedOperationException.class, () -> result.definitions().add(null));
        assertThrows(UnsupportedOperationException.class, () -> result.failures().add("extra"));
    }

    @Test
    void skipsNonJsonReadsAndContinuesAfterOneJsonReadFailure() throws Exception {
        Path pack = tempDir.resolve("pack");
        write(pack, "Server/Patchwork/Patches/Notes.txt", "must not be read");
        write(pack, "Server/Patchwork/Patches/Bad.json", patch("bad", "Server/Bad.json"));
        write(pack, "Server/Patchwork/Patches/Good.json", patch("good", "Server/Good.json"));
        List<String> reads = new ArrayList<>();

        PatchScanner scanner = new PatchScanner((source, assetPath) -> {
            reads.add(assetPath);
            if (assetPath.endsWith("Bad.json")) throw new IOException("planned read failure");
            return Files.readAllBytes(source.backingPath().resolve(assetPath));
        });
        PatchScanner.ScanResult result = scanner.scan(List.of(PatchSource.directory("pack", 1, pack)), Set.of());

        assertEquals(List.of("Server/Patchwork/Patches/Bad.json", "Server/Patchwork/Patches/Good.json"), reads);
        assertEquals(List.of("good"), result.definitions().stream().map(definition -> definition.id()).toList());
        assertEquals(List.of("Failed to parse pack:Server/Patchwork/Patches/Bad.json: planned read failure"), result.failures());
        assertEquals(Set.of("Server/Patchwork/Patches/Bad.json", "Server/Patchwork/Patches/Good.json"),
                result.definitionDependencies().stream().map(dependency -> dependency.assetPath())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(com.alechilles.patchwork.generation.GenerationDependencyIndex.Validity.INVALID,
                        com.alechilles.patchwork.generation.GenerationDependencyIndex.Validity.VALID),
                result.definitionDependencies().stream().map(dependency -> dependency.validity())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void scansValidFormatTwoSentinelDefinition() throws Exception {
        Path source = tempDir.resolve("source");
        write(source, "Server/Patchwork/Patches/v2.json", """
                {"FormatVersion":2,"Id":"v2","Target":"Server/A.json",
                 "Operations":[{"Op":"RequireFormat","Version":2}]}
                """);

        PatchScanner.ScanResult result = new PatchScanner().scan(
                List.of(PatchSource.directory("pack", 1, source)), Set.of());

        assertEquals(1, result.definitions().size());
        assertEquals(2, result.definitions().getFirst().formatVersion());
        assertEquals(List.of(), result.failures());
    }

    @Test
    void neutralRootUsesClosedSentinelFreeLanguageWhenMarkerIsAbsent() throws Exception {
        Path source = tempDir.resolve("source");
        write(source, "Server/Patchwork/Patches/modern.json", """
                {"Target":"Server/Test/A.json","Operations":[{"Op":"Remove","Path":"/Old"}]}
                """);

        PatchScanner.ScanResult result = new PatchScanner().scan(
                List.of(PatchSource.directory("pack", 1, source)), Set.of());

        assertEquals(PatchLanguage.NEUTRAL, result.definitions().getFirst().language());
        assertEquals(List.of(), result.failures());
    }

    @Test
    void neutralUnknownOperationRejectsWholeDefinitionEvenWhenOptional() throws Exception {
        Path source = tempDir.resolve("source");
        write(source, "Server/Patchwork/Patches/bad.json", """
                {"Target":"Server/Test/A.json","Operations":[{"Op":"FutureOp","Required":false}]}
                """);

        PatchScanner.ScanResult result = new PatchScanner().scan(
                List.of(PatchSource.directory("pack", 1, source)), Set.of());

        assertEquals(List.of(), result.definitions());
        assertEquals(1, result.failures().size());
    }

    @Test
    void rejectsDuplicateKeysInFormatTwoDefinitionBytes() throws Exception {
        Path source = tempDir.resolve("source");
        write(source, "Server/Patchwork/Patches/v2.json", """
                {"FormatVersion":2,"Id":"v2","Id":"duplicate","Target":"Server/A.json",
                 "Operations":[{"Op":"RequireFormat","Version":2}]}
                """);

        PatchScanner.ScanResult result = new PatchScanner().scan(
                List.of(PatchSource.directory("pack", 1, source)), Set.of());

        assertEquals(1, result.failures().size());
        assertEquals(0, result.definitions().size());
    }

    @Test
    void rejectsDuplicateFormatVersionThatWouldDowngradeFormatTwo() throws Exception {
        Path source = tempDir.resolve("source");
        write(source, "Server/Patchwork/Patches/v2.json", """
                {"FormatVersion":2,"FormatVersion":1,"Id":"v2","Target":"Server/A.json",
                 "Operations":[]}
                """);

        PatchScanner.ScanResult result = new PatchScanner().scan(
                List.of(PatchSource.directory("pack", 1, source)), Set.of());

        assertEquals(1, result.failures().size());
        assertEquals(0, result.definitions().size());
    }

    @Test
    void rejectsLenientSyntaxInFormatTwoDefinition() throws Exception {
        Path source = tempDir.resolve("source");
        write(source, "Server/Patchwork/Patches/v2.json", """
                {"FormatVersion":2,"Id":"v"+"2","Target":"Server/A.json",
                 "Operations":[{"Op":"RequireFormat","Version":2}]}
                """);

        PatchScanner.ScanResult result = new PatchScanner().scan(
                List.of(PatchSource.directory("pack", 1, source)), Set.of());

        assertEquals(1, result.failures().size());
        assertEquals(0, result.definitions().size());
    }

    @Test
    void keepsLegacyGsonLenientSyntaxCompatibility() throws Exception {
        Path source = tempDir.resolve("source");
        write(source, "Server/Patchwork/Patches/legacy.json", """
                // Legacy Patchwork definitions remain Gson-lenient.
                {Id:'legacy',Target:'Server/A.json',Operations:[]}
                """);

        PatchScanner.ScanResult result = new PatchScanner().scan(
                List.of(PatchSource.directory("pack", 1, source)), Set.of());

        assertEquals(List.of("legacy"), result.definitions().stream().map(definition -> definition.id()).toList());
        assertEquals(List.of(), result.failures());
    }

    @Test
    void rejectsV2IntentWhenPayloadNumberOverflowsProbeNumberModel() throws Exception {
        Path source = tempDir.resolve("source");
        write(source, "Server/Patchwork/Patches/v2.json", """
                {"FormatVersion":2,"FormatVersion":1,"Id":"v2","Target":"Server/A.json",
                 "Value":1e2147483649,"Operations":[]}
                """);

        PatchScanner.ScanResult result = new PatchScanner().scan(
                List.of(PatchSource.directory("pack", 1, source)), Set.of());

        assertEquals(1, result.failures().size());
        assertEquals(0, result.definitions().size());
    }

    private static String patch(String id, String target) {
        return "{ \"Id\": \"" + id + "\", \"Target\": \"" + target + "\", \"Operations\": [] }";
    }

    private static String multiTargetPatch(String id, String... targets) {
        return "{ \"Id\": \"" + id + "\", \"Targets\": [" + java.util.Arrays.stream(targets)
                .map(target -> "\"" + target + "\"").collect(java.util.stream.Collectors.joining(", ")) + "], \"Operations\": [] }";
    }

    private static void write(Path root, String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void writeArchive(Path archive, String entry, String content) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry(entry));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
