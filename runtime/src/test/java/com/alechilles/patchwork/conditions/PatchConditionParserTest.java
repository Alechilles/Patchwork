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

        PatchCondition.All all = assertInstanceOf(PatchCondition.All.class, condition);
        assertInstanceOf(PatchCondition.ModInstalled.class, all.children().get(0));
        PatchCondition.Any any = assertInstanceOf(PatchCondition.Any.class, all.children().get(1));
        assertInstanceOf(PatchCondition.AssetExists.class, any.children().get(0));
        assertInstanceOf(PatchCondition.AssetMissing.class, any.children().get(1));
        assertInstanceOf(PatchCondition.Not.class, all.children().get(2));
        assertInstanceOf(PatchCondition.TargetExists.class, all.children().get(3));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(JsonParser.parseString(
                "{\"ModVersion\":{\"Mod\":\"Example:Mod\",\"Equals\":\"1.x\"}}"
        ).getAsJsonObject()));
    }

    @Test
    void parsesEveryRetainedSimpleShapeAndLegacyNonTargetAsset() {
        assertEquals("Example:Mod", assertInstanceOf(PatchCondition.ModInstalled.class, parser.parse(JsonParser.parseString("{\"ModInstalled\":\"Example:Mod\"}").getAsJsonObject())).modId());
        assertEquals("Server/A.json", assertInstanceOf(PatchCondition.AssetExists.class, parser.parse(JsonParser.parseString("{\"AssetExists\":\"Server/A.json\"}").getAsJsonObject())).path());
        assertEquals("Server/A.json", assertInstanceOf(PatchCondition.AssetMissing.class, parser.parse(JsonParser.parseString("{\"AssetMissing\":\"Server/A.json\"}").getAsJsonObject())).path());
        assertInstanceOf(PatchCondition.TargetExists.class, parser.parse(JsonParser.parseString("{\"TargetExists\":true}").getAsJsonObject()));
        assertEquals("Example:Mod", assertInstanceOf(PatchCondition.ModVersion.class, parser.parse(JsonParser.parseString("{\"ModVersion\":{\"Mod\":\"Example:Mod\",\"AtLeast\":\"1.2\"}}").getAsJsonObject())).modId());
        PatchCondition.JsonPathExists legacy = assertInstanceOf(PatchCondition.JsonPathExists.class, parser.parse(JsonParser.parseString("{\"JsonPathExists\":{\"Asset\":\"Server/A.json\",\"Path\":\"/x\"}}").getAsJsonObject()));
        assertEquals(new ConditionSource.Asset("Server/A.json"), legacy.source());
    }

    @Test
    void retainsGameAndServerVersionAndImplicitTargetSource() {
        assertInstanceOf(PatchCondition.ServerVersion.class, parser.parse(JsonParser.parseString(
                "{\"GameVersion\":{\"AtLeast\":\"999999999999999999999.0\"}}"
        ).getAsJsonObject()));
        assertInstanceOf(PatchCondition.ServerVersion.class, parser.parse(JsonParser.parseString(
                "{\"ServerVersion\":{\"Below\":\"2.0\"}}"
        ).getAsJsonObject()));
        PatchCondition.JsonPathExists implicit = assertInstanceOf(PatchCondition.JsonPathExists.class, parser.parse(JsonParser.parseString(
                "{\"JsonPathExists\":{\"Path\":\"/enabled\"}}"
        ).getAsJsonObject()));
        assertEquals(new ConditionSource.Target(), implicit.source());
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

    @Test
    void acceptsTheEmptyJsonPointerForWholeDocumentChecks() {
        PatchCondition.JsonPathExists exists = assertInstanceOf(PatchCondition.JsonPathExists.class,
                parser.parse(JsonParser.parseString("{\"JsonPathExists\":{\"Path\":\"\"}}").getAsJsonObject()));
        PatchCondition.JsonPathEquals equals = assertInstanceOf(PatchCondition.JsonPathEquals.class,
                parser.parse(JsonParser.parseString("{\"JsonPathEquals\":{\"Path\":\"\",\"Value\":{\"enabled\":true}}}").getAsJsonObject()));

        assertEquals("", exists.path());
        assertEquals("", equals.path());
    }

    @Test
    void carriesFormatVersionIntoStrictJsonPathConditions() {
        PatchCondition.JsonPathExists strict = assertInstanceOf(PatchCondition.JsonPathExists.class,
                parser.parse(JsonParser.parseString("{\"JsonPathExists\":{\"Path\":\"/enabled\"}}").getAsJsonObject(), 2));
        assertEquals(2, strict.formatVersion());
        PatchCondition.JsonPathExists legacy = assertInstanceOf(PatchCondition.JsonPathExists.class,
                parser.parse(JsonParser.parseString("{\"JsonPathExists\":{\"Path\":\"/enabled\"}}").getAsJsonObject()));
        assertEquals(1, legacy.formatVersion());
    }

    @Test
    void rejectsMalformedStrictJsonPointerEscapes() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(JsonParser.parseString(
                "{\"JsonPathExists\":{\"Path\":\"/a~2b\"}}"
        ).getAsJsonObject(), 2));
    }
}
