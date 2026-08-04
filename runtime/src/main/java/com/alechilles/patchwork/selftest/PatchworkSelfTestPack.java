package com.alechilles.patchwork.selftest;

import java.util.List;
import java.util.Map;

/** Immutable, Patchwork-neutral fixture pack for one isolated self-test run. */
public record PatchworkSelfTestPack(List<PatchworkSelfTestCase> cases) {
    public PatchworkSelfTestPack { cases = List.copyOf(cases); }
    public static PatchworkSelfTestPack empty() { return new PatchworkSelfTestPack(List.of()); }
    /** Exercises every built-in operation and a genuine fixture-local ModData condition through the production generator. */
    public static PatchworkSelfTestPack standard() {
        return new PatchworkSelfTestPack(List.of(
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/add.json", "{}",
                        "Server/Patchwork/Patches/add.json", """
                        {"FormatVersion":2,"Id":"selftest-add-${runId}","Target":"Server/PatchworkSelfTest/add.json","Operations":[{"Op":"RequireFormat","Version":2},{"Op":"Add","Path":"/added","Value":true}]}
                        """, null, Map.of(), "Server/PatchworkSelfTest/add.json", Map.of("/added", "true")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/merge.json", "{\"settings\":{\"old\":1}}",
                        "Server/Patchwork/Patches/merge.json", """
                        {"FormatVersion":2,"Id":"selftest-merge-${runId}","Target":"Server/PatchworkSelfTest/merge.json","Operations":[{"Op":"RequireFormat","Version":2},{"Op":"Merge","Path":"/settings","Value":{"new":2}}]}
                        """, null, Map.of(), "Server/PatchworkSelfTest/merge.json", Map.of("/settings/old", "1", "/settings/new", "2")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/replace.json", "{\"value\":1}",
                        "Server/Patchwork/Patches/replace.json", """
                        {"FormatVersion":2,"Id":"selftest-replace-${runId}","Target":"Server/PatchworkSelfTest/replace.json","Operations":[{"Op":"RequireFormat","Version":2},{"Op":"Replace","Path":"/value","Value":2}]}
                        """, null, Map.of(), "Server/PatchworkSelfTest/replace.json", Map.of("/value", "2")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/remove.json", "{\"items\":[\"remove\",\"keep\"]}",
                        "Server/Patchwork/Patches/remove.json", """
                        {"FormatVersion":2,"Id":"selftest-remove-${runId}","Target":"Server/PatchworkSelfTest/remove.json","Operations":[{"Op":"RequireFormat","Version":2},{"Op":"Remove","Path":"/items/0"}]}
                        """, null, Map.of(), "Server/PatchworkSelfTest/remove.json", Map.of("/items/0", "\"keep\"")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/insert.json", "{\"items\":[{\"id\":\"anchor\"}]}",
                        "Server/Patchwork/Patches/insert.json", """
                        {"FormatVersion":2,"Id":"selftest-insert-${runId}","Target":"Server/PatchworkSelfTest/insert.json","Operations":[{"Op":"RequireFormat","Version":2},{"Op":"Insert","Path":"/items","Position":"After","Find":{"id":"anchor"},"Value":{"id":"inserted"}}]}
                        """, null, Map.of(), "Server/PatchworkSelfTest/insert.json", Map.of("/items/1/id", "\"inserted\"")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/replace-matching.json", "{\"items\":[{\"id\":\"keep\"},{\"id\":\"change\"}]}",
                        "Server/Patchwork/Patches/replace-matching.json", """
                        {"FormatVersion":2,"Id":"selftest-replace-matching-${runId}","Target":"Server/PatchworkSelfTest/replace-matching.json","Operations":[{"Op":"RequireFormat","Version":2},{"Op":"ReplaceMatching","Path":"/items","Match":{"id":"change"},"Value":{"id":"changed"}}]}
                        """, null, Map.of(), "Server/PatchworkSelfTest/replace-matching.json", Map.of("/items/1/id", "\"changed\"")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/remove-matching.json", "{\"items\":[{\"id\":\"remove\"},{\"id\":\"keep\"}]}",
                        "Server/Patchwork/Patches/remove-matching.json", """
                        {"FormatVersion":2,"Id":"selftest-remove-matching-${runId}","Target":"Server/PatchworkSelfTest/remove-matching.json","Operations":[{"Op":"RequireFormat","Version":2},{"Op":"RemoveMatching","Path":"/items","Match":{"id":"remove"}}]}
                        """, null, Map.of(), "Server/PatchworkSelfTest/remove-matching.json", Map.of("/items/0/id", "\"keep\"")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/move-matching.json", "{\"items\":[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"c\"}]}",
                        "Server/Patchwork/Patches/move-matching.json", """
                        {"FormatVersion":2,"Id":"selftest-move-matching-${runId}","Target":"Server/PatchworkSelfTest/move-matching.json","Operations":[{"Op":"RequireFormat","Version":2},{"Op":"MoveMatching","Path":"/items","Match":{"id":"b"},"Position":"After","Find":{"id":"c"}}]}
                        """, null, Map.of(), "Server/PatchworkSelfTest/move-matching.json", Map.of("/items/1/id", "\"c\"", "/items/2/id", "\"b\"")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/matching.json", "{\"rows\":[{\"id\":\"a\",\"old\":true}]}",
                        "Server/Patchwork/Patches/matching.json", """
                        {"Id":"selftest-matching-${runId}","Target":"Server/PatchworkSelfTest/matching.json","Operations":[{"Op":"MergeMatching","Path":"/rows","Match":{"id":"a"},"Value":{"merged":true}},{"Op":"UpsertMatching","Path":"/rows","Match":{"id":"b"},"Value":{"id":"b","upserted":true}}]}
                        """, null, Map.of(), "Server/PatchworkSelfTest/matching.json", Map.of("/rows/0/merged", "true", "/rows/1/upserted", "true")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/cross-asset.json", "{\"destination\":{\"old\":1}}",
                        "Server/Patchwork/Patches/cross-asset.json", """
                        {"Id":"selftest-cross-asset-${runId}","Target":"Server/PatchworkSelfTest/cross-asset.json","Operations":[{"Op":"OverlayFromAsset","Source":"Server/PatchworkSelfTest/source.json"},{"Op":"MergeObjectFromAsset","Source":"Server/PatchworkSelfTest/source.json","SourcePath":"/shared","Path":"/destination"}]}
                        """, null, Map.of(), "Server/PatchworkSelfTest/cross-asset.json", Map.of("/overlayed", "true", "/destination/old", "1", "/destination/fromSource", "2"),
                        Map.of("Server/PatchworkSelfTest/source.json", "{\"overlayed\":true,\"shared\":{\"fromSource\":2}}")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/glob-one.json", "{\"enabled\":false}",
                        "Server/Patchwork/Patches/glob.json", """
                        {"Id":"selftest-glob-${runId}","Target":"glob:Server/PatchworkSelfTest/glob-*.json","Operations":[{"Op":"Replace","Path":"/enabled","Value":true}]}
                        """, null, Map.of(), "Server/PatchworkSelfTest/glob-one.json", Map.of("/enabled", "true"),
                        Map.of("Server/PatchworkSelfTest/glob-two.json", "{\"enabled\":false}")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/conflict-policy.json", "{\"value\":0}",
                        "Server/Patchwork/Patches/conflict-first.json", """
                        {"Id":"selftest-conflict-a-first-${runId}","Target":"Server/PatchworkSelfTest/conflict-policy.json","Operations":[{"Op":"Replace","Path":"/value","Value":1}]}
                        """, null, Map.of(), "Server/PatchworkSelfTest/conflict-policy.json", Map.of("/value", "2"),
                        Map.of("Server/Patchwork/Patches/conflict-allow.json", "{\"Id\":\"selftest-conflict-b-allow-${runId}\",\"Target\":\"Server/PatchworkSelfTest/conflict-policy.json\",\"ConflictPolicy\":\"Allow\",\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":2}]}")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/condition.json", "{\"enabled\":false,\"value\":1}",
                        "Server/Patchwork/Patches/condition.json", """
                        {"FormatVersion":2,"Id":"selftest-condition-${runId}","Target":"Server/PatchworkSelfTest/condition.json","When":{"JsonPathEquals":{"Source":{"Type":"ModData","Mod":"Patchwork:SelfTest","Path":"settings.json"},"Path":"/enabled","Value":true}},"Operations":[{"Op":"RequireFormat","Version":2},{"Op":"Replace","Path":"/value","Value":3}]}
                        """,
                        "Patchwork:SelfTest", Map.of("settings.json", "{\"enabled\":true}"), "Server/PatchworkSelfTest/condition.json", Map.of("/value", "3"))));
    }
}
