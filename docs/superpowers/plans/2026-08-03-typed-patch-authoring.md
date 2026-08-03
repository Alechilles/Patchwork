# Typed Patch Authoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Patchwork's native Hytale asset type guided recursive schemas for `When`, matchers, and arbitrary JSON while preserving the exact portable JSON representation.

**Architecture:** Add small BSON-pass-through codecs that implement Hytale `NamedSchema` and generate recursive schema definitions through `SchemaContext.refDefinition(...)`. Keep `PatchDefinitionAsset`, `PatchOperationAsset`, `portableSource`, and all runtime parsers as the data and validation authority; the new code changes only native authoring schemas and their beginner-facing documentation.

**Tech Stack:** Java 25, Hytale release 0.5.7 codec/schema API, BSON, Gson, JUnit 5, Maven Wrapper.

## Global Constraints

- Preserve the documented Patchwork format-2 JSON shapes exactly; do not add `Type`, `$Editor`, wrapper metadata, or normalization.
- Keep BSON values and `portableSource` as the serialization representation; never coerce arbitrary numbers through Java numeric primitives.
- Use Hytale `SchemaContext.refDefinition(...)` for every recursive edge.
- Leave runtime validation in `PatchDefinition.parseAll`, `PatchConditionParser`, `PatchOperation`, and `JsonMatcher`.
- Keep accepted legacy extensions, lowercase enum spellings, JSON `null`, and high-precision numbers lossless.
- Use Java 25 and the existing Hytale system dependency path.
- Run `./mvnw.cmd test` after source changes and `./mvnw.cmd -pl standalone -am verify` before installing a standalone artifact.

---

## File Structure

- Create `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchConditionCodec.java`: pass-through `BsonDocument` codec and named recursive condition schema.
- Create `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchMatcherCodec.java`: pass-through `BsonDocument` codec and named recursive matcher schema.
- Create `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchMatcherValueSchema.java`: named recursive union used by ordinary matcher properties.
- Create `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchJsonObjectCodec.java`: object-only pass-through codec and named recursive JSON-object schema.
- Modify `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchJsonValueCodec.java`: retain pass-through decode/encode and replace the empty schema with the named six-kind recursive JSON union.
- Modify `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchDefinitionAsset.java`: use `PatchConditionCodec.INSTANCE` for `When`.
- Modify `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchOperationAsset.java`: use typed codecs for `Match`, `Find`, `Existing`, and `Options`.
- Create `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNativeAuthoringSchemaTest.java`: inspect real generated Hytale schemas, named definitions, recursive references, titles, and documentation.
- Modify `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchDefinitionAssetTest.java`: add one representative lossless recursive portable round-trip without duplicating existing precision, legacy-extension, lowercase-enum, duplicate-key, or trailing-content coverage.
- Modify `CHANGELOG.md`: describe the new beginner-facing native editor support without claiming a new portable format or runtime behavior.

### Task 1: Typed recursive `When` conditions

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchConditionCodec.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchDefinitionAsset.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNativeAuthoringSchemaTest.java`

**Interfaces:**
- Consumes: `Codec.BSON_DOCUMENT`, `RawJsonReader.readBsonValue(...)`, `SchemaContext.refDefinition(...)`, and the existing portable condition grammar in `docs/authoring-kit/v2/patch-definition.schema.json`.
- Produces: `PatchConditionCodec.INSTANCE : Codec<BsonDocument>` and named definition `PatchCondition`.

- [ ] **Step 1: Write the failing condition-schema test**

Add a test that generates the real asset schema and checks the observable authoring contract:

```java
@Test
void whenUsesDocumentedNamedRecursiveConditionChoices() {
    SchemaContext context = new SchemaContext();
    ObjectSchema definition = (ObjectSchema) PatchDefinitionAsset.CODEC.toSchema(context);

    assertEquals("other.json#/definitions/PatchCondition",
            definition.getProperties().get("When").getRef());
    Schema condition = context.getOtherDefinitions().get("PatchCondition");
    assertEquals(Set.of("ModInstalled", "ModVersion", "ServerVersion", "GameVersion",
                    "AssetExists", "AssetMissing", "TargetExists", "TargetProvidedBy",
                    "JsonPathExists", "JsonPathEquals", "All", "Any", "Not"),
            variantTitles(condition));
    assertTrue(containsRef(condition, "other.json#/definitions/PatchCondition"));
    assertEverySchemaChoiceDocumented(condition);
}
```

The test helper must traverse real `oneOf`/`anyOf`, object properties, array items, and schema references. It must not inspect source text or use mocks.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./mvnw.cmd -pl runtime -Dtest=PatchNativeAuthoringSchemaTest#whenUsesDocumentedNamedRecursiveConditionChoices test
```

Expected: FAIL because `When` still uses the untyped `Codec.BSON_DOCUMENT` schema and `PatchCondition` is absent.

- [ ] **Step 3: Implement the pass-through condition codec**

Implement these exact public/package interfaces:

```java
final class PatchConditionCodec implements Codec<BsonDocument>, NamedSchema {
    static final PatchConditionCodec INSTANCE = new PatchConditionCodec();

    @Override public String getSchemaName() { return "PatchCondition"; }
    @Override public BsonDocument decode(BsonValue value, ExtraInfo info) {
        return Codec.BSON_DOCUMENT.decode(value, info);
    }
    @Override public BsonValue encode(BsonDocument value, ExtraInfo info) {
        return Codec.BSON_DOCUMENT.encode(value, info);
    }
    @Override public BsonDocument decodeJson(RawJsonReader reader, ExtraInfo info) throws IOException {
        return Codec.BSON_DOCUMENT.decodeJson(reader, info);
    }
}
```

`toSchema(...)` must return a documented `Schema` with 13 titled `oneOf` object variants. Each variant is a closed `ObjectSchema` containing its existing condition property plus optional documented `$Comment`. Use closed nested object schemas for asset references, version comparisons, JSON-path fields, and `Target`/`Asset`/`ModData` source alternatives. Use `context.refDefinition(INSTANCE)` for `All` array items, `Any` array items, and `Not`.

Use existing portable fields only:

```java
private static ObjectSchema recursiveList(
        SchemaContext context, String key, String title, String documentation) {
    ArraySchema values = new ArraySchema(context.refDefinition(INSTANCE));
    values.setMinItems(1);
    return conditionVariant(key, values, title, documentation);
}
```

`JsonPathEquals.Value` and its legacy `Equals` alias should initially use `PatchJsonValueCodec.INSTANCE.toSchema(context)`; Task 3 converts those edges to named JSON-value references.

- [ ] **Step 4: Wire `When` to the new codec**

Replace only the child codec:

```java
.append(new KeyedCodec<>("When", PatchConditionCodec.INSTANCE),
        (asset, value) -> asset.when = value, asset -> asset.when)
```

Do not change `fromPortableJson`, `toPortableJson`, `copyFrom`, or runtime validation.

- [ ] **Step 5: Run the focused condition test and existing asset tests**

Run:

```bash
./mvnw.cmd -pl runtime -Dtest=PatchNativeAuthoringSchemaTest,PatchDefinitionAssetTest test
```

Expected: PASS with the condition definition present and existing portable behavior unchanged.

- [ ] **Step 6: Commit the condition slice**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/authoring/PatchConditionCodec.java \
  runtime/src/main/java/com/alechilles/patchwork/authoring/PatchDefinitionAsset.java \
  runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNativeAuthoringSchemaTest.java
git commit -m "Feat: add typed patch conditions"
```

### Task 2: Recursive matchers

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchMatcherCodec.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchMatcherValueSchema.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchOperationAsset.java`
- Modify: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNativeAuthoringSchemaTest.java`

**Interfaces:**
- Consumes: `PatchJsonValueCodec.INSTANCE`, `SchemaContext.refDefinition(...)`, and the existing `BsonDocument` fields in `PatchOperationAsset`.
- Produces: `PatchMatcherCodec.INSTANCE`, named definition `PatchMatcher`, and named definition `PatchMatcherValue`.

- [ ] **Step 1: Write the failing matcher-schema test**

```java
@Test
void matcherFieldsUseDocumentedRecursiveOperatorAndOrdinaryKeyChoices() {
    SchemaContext context = new SchemaContext();
    ObjectSchema operation = PatchOperationAsset.CODEC.toSchema(context);

    for (String field : List.of("Match", "Find", "Existing")) {
        assertEquals("other.json#/definitions/PatchMatcher",
                operation.getProperties().get(field).getRef(), field);
    }
    Schema matcher = context.getOtherDefinitions().get("PatchMatcher");
    assertEquals(Set.of("Exact value", "Contains", "Object fields"), variantTitles(matcher));
    assertTrue(containsProperty(matcher, "$Equals"));
    assertTrue(containsProperty(matcher, "$Contains"));
    assertTrue(containsRef(matcher, "other.json#/definitions/PatchMatcher"));
    assertTrue(context.getOtherDefinitions().containsKey("PatchMatcherValue"));
}
```

- [ ] **Step 2: Run the matcher test and verify RED**

Run:

```bash
./mvnw.cmd -pl runtime -Dtest=PatchNativeAuthoringSchemaTest#matcherFieldsUseDocumentedRecursiveOperatorAndOrdinaryKeyChoices test
```

Expected: FAIL because all three fields still expose `Codec.BSON_DOCUMENT` and no matcher definitions exist.

- [ ] **Step 3: Implement the matcher codec and matcher-value schema**

`PatchMatcherCodec` mirrors the condition codec's BSON pass-through methods and returns a documented three-choice schema:

```java
Schema matcher = new Schema();
matcher.setOneOf(
        exactValueVariant(context),
        containsVariant(context.refDefinition(INSTANCE)),
        ordinaryFieldsVariant(context.refDefinition(PatchMatcherValueSchema.INSTANCE)));
```

The exact variant is a closed object requiring `$Equals`. The contains variant is a closed object requiring `$Contains`. The ordinary-fields variant keeps additional properties open with the `PatchMatcherValue` schema and applies a negative-lookahead property-name pattern such as `^(?!\\$).+` so reserved `$` keys are not suggested. Runtime validation continues to enforce sole-key operators and non-empty ordinary objects.

`PatchMatcherValueSchema` implements `SchemaConvertable<Void>, NamedSchema`, returns `PatchMatcherValue` from `getSchemaName()`, and produces scalar, array, and nested-matcher choices. The nested matcher edge must call `context.refDefinition(PatchMatcherCodec.INSTANCE)`.

- [ ] **Step 4: Wire all matcher fields**

Replace the three `Codec.BSON_DOCUMENT` child codecs with `PatchMatcherCodec.INSTANCE`. Preserve their distinct existing field tooltips.

- [ ] **Step 5: Run focused authoring tests**

Run:

```bash
./mvnw.cmd -pl runtime -Dtest=PatchNativeAuthoringSchemaTest,PatchDefinitionAssetTest test
```

Expected: PASS with all matcher fields sharing one named recursive schema.

- [ ] **Step 6: Commit the matcher slice**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/authoring/PatchMatcherCodec.java \
  runtime/src/main/java/com/alechilles/patchwork/authoring/PatchMatcherValueSchema.java \
  runtime/src/main/java/com/alechilles/patchwork/authoring/PatchOperationAsset.java \
  runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNativeAuthoringSchemaTest.java
git commit -m "Feat: add typed recursive patch matchers"
```

### Task 3: Recursive arbitrary JSON and object-only macro options

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchJsonObjectCodec.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchJsonValueCodec.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchConditionCodec.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchMatcherCodec.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchMatcherValueSchema.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchOperationAsset.java`
- Modify: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNativeAuthoringSchemaTest.java`
- Modify: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchDefinitionAssetTest.java`

**Interfaces:**
- Consumes: existing pass-through `BsonValue` implementation and `PortableJsonBsonDocument` lossless mirroring.
- Produces: named `PatchJsonValue`, named `PatchJsonObject`, `Value` accepting six JSON kinds, and object-only `Options`.

- [ ] **Step 1: Write the failing JSON-schema test**

```java
@Test
void valueAndOptionsUseRecursiveJsonSchemasWithoutChangingPortableShape() {
    SchemaContext context = new SchemaContext();
    ObjectSchema operation = PatchOperationAsset.CODEC.toSchema(context);

    assertEquals("other.json#/definitions/PatchJsonValue",
            operation.getProperties().get("Value").getRef());
    assertEquals("other.json#/definitions/PatchJsonObject",
            operation.getProperties().get("Options").getRef());
    Schema value = context.getOtherDefinitions().get("PatchJsonValue");
    assertEquals(Set.of("Null", "Boolean", "Number", "String", "Array", "Object"),
            variantTitles(value));
    assertTrue(containsRef(value, "other.json#/definitions/PatchJsonValue"));
    assertTrue(containsRef(value, "other.json#/definitions/PatchJsonObject"));
}
```

Also add a real decode/encode test containing nested `All`/`Not`, `$Contains`/`$Equals`, a macro `Options` object, JSON `null`, and a high-precision decimal. Compare `asset.toPortableJson()` with `JsonParser.parseString(source)` so the expected value is independently derived from the literal fixture.

- [ ] **Step 2: Run both new tests and verify RED**

Run:

```bash
./mvnw.cmd -pl runtime -Dtest=PatchNativeAuthoringSchemaTest#valueAndOptionsUseRecursiveJsonSchemasWithoutChangingPortableShape,PatchDefinitionAssetTest#nativeCodecPreservesRecursiveAuthoringShapesExactly test
```

Expected: the schema test FAILS because `Value` is still an unnamed empty schema and `Options` is an untyped BSON document. The portable round-trip test may already pass; retain it as a compatibility guard, not as proof of the new schema behavior.

- [ ] **Step 3: Implement named recursive JSON codecs**

Update the value codec declaration and schema name:

```java
final class PatchJsonValueCodec implements Codec<BsonValue>, NamedSchema {
    @Override public String getSchemaName() { return "PatchJsonValue"; }
}
```

Keep all existing decode/encode methods byte-for-byte equivalent. Build a six-choice schema using `NullSchema`, `Codec.BOOLEAN.toSchema(context)`, `Codec.DOUBLE.toSchema(context)`, `Codec.STRING.toSchema(context)`, `new ArraySchema(context.refDefinition(INSTANCE))`, and `context.refDefinition(PatchJsonObjectCodec.INSTANCE)`. Give every choice a nonblank title and beginner-facing description.

`PatchJsonObjectCodec` implements `Codec<BsonDocument>, NamedSchema`, delegates decode/encode/decodeJson to `Codec.BSON_DOCUMENT`, names itself `PatchJsonObject`, and returns:

```java
ObjectSchema object = new ObjectSchema();
object.setTitle("Object");
object.setMarkdownDescription("A JSON object. Add any property names required by the target asset or macro.");
object.setAdditionalProperties(context.refDefinition(PatchJsonValueCodec.INSTANCE));
return object;
```

- [ ] **Step 4: Replace every arbitrary-JSON schema edge with a named reference**

Use `context.refDefinition(PatchJsonValueCodec.INSTANCE)` for `JsonPathEquals.Value`, `JsonPathEquals.Equals`, matcher `$Equals`, and exact array items in `PatchMatcherValueSchema`. Use `PatchJsonObjectCodec.INSTANCE` for `Options` while keeping its Java field type `BsonDocument`.

- [ ] **Step 5: Run focused authoring tests**

Run:

```bash
./mvnw.cmd -pl runtime -Dtest=PatchNativeAuthoringSchemaTest,PatchDefinitionAssetTest test
```

Expected: PASS; all five named recursive definitions terminate, and portable fixtures remain exact.

- [ ] **Step 6: Commit the JSON slice**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/authoring/PatchJsonObjectCodec.java \
  runtime/src/main/java/com/alechilles/patchwork/authoring/PatchJsonValueCodec.java \
  runtime/src/main/java/com/alechilles/patchwork/authoring/PatchConditionCodec.java \
  runtime/src/main/java/com/alechilles/patchwork/authoring/PatchMatcherCodec.java \
  runtime/src/main/java/com/alechilles/patchwork/authoring/PatchMatcherValueSchema.java \
  runtime/src/main/java/com/alechilles/patchwork/authoring/PatchOperationAsset.java \
  runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNativeAuthoringSchemaTest.java \
  runtime/src/test/java/com/alechilles/patchwork/authoring/PatchDefinitionAssetTest.java
git commit -m "Feat: add recursive JSON authoring schemas"
```

### Task 4: Documentation, engine-reference validation, packaging, and installation

**Files:**
- Modify: `CHANGELOG.md`
- Verify: all changed Java files and the standalone artifact
- Install: `C:/Users/22ale/AppData/Roaming/Hytale/UserData/Mods/Patchwork v1.1.0.jar`

**Interfaces:**
- Consumes: all completed schema slices and the existing standalone Maven module.
- Produces: user-facing release note, fully verified build, and installed mod jar.

- [ ] **Step 1: Update the changelog**

Add one concise entry saying the native Patch asset editor now guides recursive `When` conditions, `$Equals`/`$Contains` matchers, arbitrary JSON `Value`, and object-only macro `Options`, with no portable-format change.

- [ ] **Step 2: Run focused tests after documentation integration**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchNativeAuthoringSchemaTest,PatchDefinitionAssetTest test
```

Expected: PASS.

- [ ] **Step 3: Validate engine references against Hytale release 0.5.7**

Run Hytale Workshop `validate_hytale_code_refs` for every changed Java source file that imports `com.hypixel.hytale.*`. Resolve every reported `not_found`; report `unverifiable` separately rather than treating it as a pass.

- [ ] **Step 4: Run the complete test suite**

```bash
./mvnw.cmd test
```

Expected: reactor success with zero failures and zero errors.

- [ ] **Step 5: Verify the standalone package**

```bash
./mvnw.cmd -pl standalone -am verify
```

Expected: reactor success and a freshly built shaded standalone jar.

- [ ] **Step 6: Inspect the final diff and commit documentation**

```bash
git diff --check
git status --short
git diff --stat
git add CHANGELOG.md
git commit -m "Docs: describe guided patch authoring"
```

- [ ] **Step 7: Install only the verified standalone jar**

Resolve the exact shaded jar under `standalone/target`, confirm there is exactly one Patchwork jar in the target Mods directory, copy the verified artifact to `C:/Users/22ale/AppData/Roaming/Hytale/UserData/Mods/Patchwork v1.1.0.jar`, and compare SHA-256 checksums. Do not start or leave a Hytale server process running.

- [ ] **Step 8: Final clean-state verification**

```bash
git status --short
git log -5 --oneline
```

Expected: clean worktree with the plan, three feature slices, and documentation commits visible.
