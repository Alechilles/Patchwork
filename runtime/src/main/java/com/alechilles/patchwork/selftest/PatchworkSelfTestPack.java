package com.alechilles.patchwork.selftest;

import java.util.List;
import java.util.Map;

/** Immutable, Patchwork-neutral fixture pack for one isolated self-test run. */
public record PatchworkSelfTestPack(List<PatchworkSelfTestCase> cases) {
    public PatchworkSelfTestPack { cases = List.copyOf(cases); }
    public static PatchworkSelfTestPack empty() { return new PatchworkSelfTestPack(List.of()); }
    /** Exercises an ordinary operation and a genuine fixture-local ModData condition through the production generator. */
    public static PatchworkSelfTestPack standard() {
        return new PatchworkSelfTestPack(List.of(
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/ordinary.json", "{\"value\":1}",
                        "Server/Patchwork/Patches/ordinary.json", "{\"Id\":\"selftest-ordinary-${runId}\",\"Target\":\"Server/PatchworkSelfTest/ordinary.json\",\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":2}]}",
                        null, Map.of(), "Server/PatchworkSelfTest/ordinary.json", Map.of("/value", "2")),
                new PatchworkSelfTestCase("Server/PatchworkSelfTest/condition.json", "{\"enabled\":false,\"value\":1}",
                        "Server/Patchwork/Patches/condition.json", "{\"Id\":\"selftest-condition-${runId}\",\"Target\":\"Server/PatchworkSelfTest/condition.json\",\"When\":{\"JsonPathEquals\":{\"Source\":{\"Type\":\"ModData\",\"Mod\":\"Patchwork:SelfTest\",\"Path\":\"settings.json\"},\"Path\":\"/enabled\",\"Value\":true}},\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":3}]}",
                        "Patchwork:SelfTest", Map.of("settings.json", "{\"enabled\":true}"), "Server/PatchworkSelfTest/condition.json", Map.of("/value", "3"))));
    }
}
