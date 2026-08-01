package com.alechilles.patchwork.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Defines deterministic, host-independent candidate identity and ordering. */
final class PatchworkRuntimeCandidateTest {
    private static final Path SERVER = Path.of("server", "world");

    @Test
    void ranksCompatibleReleaseAndOriginBeforeStableProviderTieBreakers() {
        // Catches an election mutation that compares versions lexically, treats prereleases as releases,
        // or lets origin outrank a newer runtime.
        var olderStandalone = candidate("a", PatchworkRuntimeOrigin.STANDALONE, "1.9.0", "a.plugin", "a.jar", 1);
        var newerEmbedded = candidate("b", PatchworkRuntimeOrigin.EMBEDDED, "2.0.0", "b.plugin", "b.jar", 1);
        var release = candidate("c", PatchworkRuntimeOrigin.EMBEDDED, "2.0.0", "c.plugin", "c.jar", 1);
        var prerelease = candidate("d", PatchworkRuntimeOrigin.STANDALONE, "2.0.0-rc.1", "d.plugin", "d.jar", 1);
        var sameVersionStandalone = candidate("e", PatchworkRuntimeOrigin.STANDALONE, "2.0.0", "z.plugin", "z.jar", 1);
        var providerTie = candidate("f", PatchworkRuntimeOrigin.STANDALONE, "2.0.0", "a.plugin", "z.jar", 1);
        var pathTie = candidate("g", PatchworkRuntimeOrigin.STANDALONE, "2.0.0", "a.plugin", "a.jar", 1);
        var ordered = List.of(olderStandalone, newerEmbedded, prerelease, release, sameVersionStandalone, providerTie, pathTie).stream().sorted().toList();

        assertEquals(List.of(pathTie, providerTie, sameVersionStandalone, newerEmbedded, release, prerelease, olderStandalone), ordered);
    }

    @Test
    void makesIncompatibleCandidatesPassiveAndResolvesSharedRootFromEveryHost() {
        // Catches a mutation that admits a mismatched ABI or preserves provider-specific data roots.
        var standalone = candidate("standalone", PatchworkRuntimeOrigin.STANDALONE, "1.0.0", "p", "standalone.jar", 1);
        var embedded = new PatchworkRuntimeCandidate("embedded", PatchworkRuntimeOrigin.EMBEDDED, "1.0.0", 2,
                "p", "1", Path.of("server", "mods", "Alec's Tamework", "runtime.jar"), SERVER.resolve("mods").resolve("Alec's Tamework"), bridge());

        assertTrue(standalone.compatibleWith(1));
        assertTrue(!embedded.compatibleWith(1));
        assertTrue(standalone.sharedDataRoot().endsWith(Path.of("mods", "Alechilles_Patchwork")));
        assertEquals(standalone.sharedDataRoot(), embedded.sharedDataRoot());
    }

    @Test
    void appliesSemanticVersionPrereleaseAndBuildPrecedence() {
        // Catches lexical prerelease comparison and treating build metadata as precedence.
        assertTrue(candidate("a", PatchworkRuntimeOrigin.EMBEDDED, "1.0.0-rc.10", "a", "a", 1)
                .compareTo(candidate("b", PatchworkRuntimeOrigin.EMBEDDED, "1.0.0-rc.2", "b", "b", 1)) < 0);
        assertEquals(0, candidate("a", PatchworkRuntimeOrigin.EMBEDDED, "1.0.0+one", "a", "a", 1)
                .compareTo(candidate("b", PatchworkRuntimeOrigin.EMBEDDED, "1.0.0+two", "a", "a", 1)));
        assertTrue(candidate("a", PatchworkRuntimeOrigin.EMBEDDED, "2.0.0", "a", "a", 1)
                .compareTo(candidate("b", PatchworkRuntimeOrigin.STANDALONE, "1.9.9", "b", "b", 1)) < 0);
    }

    @Test
    void handlesUnboundedVersionsAndRejectsMalformedSemanticVersions() {
        // Catches bounded numeric parsing and accepting empty or malformed build/prerelease suffixes.
        assertTrue(candidate("a", PatchworkRuntimeOrigin.EMBEDDED, "999999999999999999999.0.0", "a", "a", 1)
                .compareTo(candidate("b", PatchworkRuntimeOrigin.EMBEDDED, "2.0.0", "b", "b", 1)) < 0);
        for (String invalid : List.of("1.0", "1.0.0.", "1.0.0-", "1.0.0+", "1.0.0+bad..meta", "01.0.0")) assertThrows(IllegalArgumentException.class,
                () -> candidate("bad", PatchworkRuntimeOrigin.EMBEDDED, invalid, "a", "a", 1).compareTo(candidate("ok", PatchworkRuntimeOrigin.EMBEDDED, "1.0.0", "b", "b", 1)));
    }

    @Test
    void rejectsBlankRequiredCandidateStringsAfterStrippingWhitespace() {
        // Catches accepting whitespace-only provider identity or version fields that cannot safely participate in election.
        assertThrows(IllegalArgumentException.class, () -> candidate("   ", PatchworkRuntimeOrigin.STANDALONE, "1.0.0", "plugin", "a.jar", 1));
        assertThrows(IllegalArgumentException.class, () -> candidate("provider", PatchworkRuntimeOrigin.STANDALONE, " \t", "plugin", "a.jar", 1));
    }

    @Test
    void acceptsHyphenatedPrereleaseIdentifiersAndRejectsTrailingEmptyIdentifiers() {
        // Catches splitting SemVer prerelease identifiers at every hyphen or silently accepting an empty final identifier.
        assertTrue(candidate("a", PatchworkRuntimeOrigin.STANDALONE, "1.0.0-alpha-beta", "a", "a", 1)
                .compareTo(candidate("b", PatchworkRuntimeOrigin.STANDALONE, "1.0.0", "b", "b", 1)) > 0);
        assertThrows(IllegalArgumentException.class, () -> candidate("bad", PatchworkRuntimeOrigin.STANDALONE, "1.0.0-alpha.", "a", "a", 1)
                .compareTo(candidate("ok", PatchworkRuntimeOrigin.STANDALONE, "1.0.0", "b", "b", 1)));
    }

    private static PatchworkRuntimeCandidate candidate(String providerId, PatchworkRuntimeOrigin origin, String runtimeVersion,
                                                        String pluginId, String source, int abi) {
        return new PatchworkRuntimeCandidate(providerId, origin, runtimeVersion, abi, pluginId, "1", SERVER.resolve("mods").resolve(source),
                SERVER.resolve("mods").resolve(origin == PatchworkRuntimeOrigin.STANDALONE ? "Alechilles_Patchwork" : "Alec's Tamework"), bridge());
    }

    private static PatchworkCoordinatorBridge bridge() {
        return new PatchworkCoordinatorBridge() { };
    }
}
