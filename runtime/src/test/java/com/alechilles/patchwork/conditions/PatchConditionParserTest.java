package com.alechilles.patchwork.conditions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Compatibility tests for the Patchwork condition JSON grammar. */
final class PatchConditionParserTest {
    private final PatchConditionParser parser = new PatchConditionParser();

    @Test
    void parsesExplicitAndLegacyJsonSources() {
        PatchCondition equals = parser.parse(JsonParser.parseString("""
                {"JsonPathEquals":{"Source":{"Type":"Target"},"Path":"/Enabled","Equals":true}}
                """).getAsJsonObject());
        PatchCondition exists = parser.parse(JsonParser.parseString("""
                {"JsonPathExists":{"Source":{"Type":"Asset","Path":"Server/Config/Other.json"},"Path":"/Feature"}}
                """).getAsJsonObject());
        PatchCondition modData = parser.parse(JsonParser.parseString("""
                {"JsonPathEquals":{"Source":{"Type":"ModData","Mod":"Example:Mod","Path":"config/settings.json"},"Path":"/my/example/field","Value":true}}
                """).getAsJsonObject());
        PatchCondition legacy = parser.parse(JsonParser.parseString("""
                {"JsonPathExists":{"Asset":"$Target","Path":"/Feature"}}
                """).getAsJsonObject());

        assertEquals(new ConditionSource.Target(), assertInstanceOf(PatchCondition.JsonPathEquals.class, equals).source());
        assertEquals(new ConditionSource.Asset("Server/Config/Other.json"), assertInstanceOf(PatchCondition.JsonPathExists.class, exists).source());
        assertEquals(new ConditionSource.ModData("Example:Mod", "config/settings.json"), assertInstanceOf(PatchCondition.JsonPathEquals.class, modData).source());
        assertEquals(new ConditionSource.Target(), assertInstanceOf(PatchCondition.JsonPathExists.class, legacy).source());
    }

    @Test
    void preservesCompositesAndLegacyVersionMatchers() {
        PatchCondition condition = parser.parse(JsonParser.parseString("""
                {"All":[
                  {"ModInstalled":"Example:Mod"},
                  {"Any":[{"AssetExists":"Server/A.json"},{"AssetMissing":{"Asset":"$Target"}}]},
                  {"Not":{"GameVersion":{"AtLeast":"1.2.3"}}},
                  {"TargetExists":true}
                ],"$Comment":"ignored"}
                """).getAsJsonObject());

        assertInstanceOf(PatchCondition.All.class, condition);
        assertThrows(IllegalArgumentException.class, () -> parser.parse(JsonParser.parseString(
                "{\"ModVersion\":{\"Mod\":\"Example:Mod\",\"Equals\":\"1.x\"}}"
        ).getAsJsonObject()));
    }

    @Test
    void rejectsRetiredAndAmbiguousSourceForms() {
        IllegalArgumentException retired = assertThrows(IllegalArgumentException.class, () -> parser.parse(JsonParser.parseString(
                "{\"TameworkSetting\":{\"Path\":\"enabled\",\"Value\":true}}"
        ).getAsJsonObject()));
        assertEquals(true, retired.getMessage().contains("retired"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(JsonParser.parseString(
                "{\"JsonPathExists\":{\"Asset\":\"Server/A.json\",\"Source\":{\"Type\":\"Target\"},\"Path\":\"/a\"}}"
        ).getAsJsonObject()));
    }
}
