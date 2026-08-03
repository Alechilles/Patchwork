# Advanced Patch Language and Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Patchwork's neutral unversioned language, immutable source snapshot, mutation traces, matching merge/upsert operations, cross-asset merge operations, explicit glob targets, and generation dependency metadata with first-class native Asset Editor support.

**Architecture:** Keep `PatchEngine` pure by passing an immutable `GenerationAssetSnapshot` through an `ApplicationContext`; filesystem discovery and snapshot capture stay in generation/discovery. Parse neutral-root definitions with a root-selected `PatchLanguage` while preserving direct legacy parsing and explicit format 1/2 semantics. Expand target selectors before grouping definitions, bind each expansion to an ordinary concrete `PatchDefinition`, and return effects plus dependencies in the immutable generation plan.

**Tech Stack:** Java 25, Gson 2.11, Hytale 0.5.7 asset codecs/schemas, JUnit 5, Jimfs, NetworkNT JSON Schema Validator, Maven Wrapper.

## Global Constraints

- New files below `Server/Patchwork/Patches` with no `FormatVersion` use the modern neutral language and never require `RequireFormat`.
- Existing explicit format 1/2 definitions retain exact behavior; missing format below `Server/Tamework/Patches` remains legacy format 1.
- Unknown neutral root fields, operation fields, and operation names reject the whole definition even when `Required` is false.
- `Required: false` only skips understood applicability failures.
- The generated pack ID `Alechilles:Patchwork_GeneratedPatches` is excluded from scanning, inventory, source lookup, and target expansion.
- Source operations read original winning bytes from one immutable generation snapshot, never patched output.
- Exact and expanded target paths are normalized, deduplicated, and sorted by unsigned UTF-8 bytes.
- Native Asset Editor support, portable schema/capabilities, conformance fixtures, docs, and tests ship in the same task as each public language feature.
- Do not serialize editor-only wrappers, discriminators, or metadata; preserve accepted versioned/legacy documents losslessly.
- Use Java 25 and preserve the existing Hytale system dependency path.
- Run `./mvnw.cmd test` after code changes.

---

## File Structure

- Create `runtime/src/main/java/com/alechilles/patchwork/format/PatchLanguage.java`: root-selected grammar profile independent of the legacy integer marker.
- Create `runtime/src/main/java/com/alechilles/patchwork/generation/GenerationAssetSnapshot.java`: immutable original winning asset inventory and bytes.
- Create `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchTargetSelector.java`: exact or explicit `glob:` selector parser.
- Create `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchTargetExpander.java`: deterministic selector expansion over a snapshot.
- Create `runtime/src/main/java/com/alechilles/patchwork/engine/MutationEffect.java`: concrete write/membership/order effect with a value fingerprint but no raw value.
- Create `runtime/src/main/java/com/alechilles/patchwork/generation/GenerationDependencyIndex.java`: definition, target, source-asset, and glob dependencies.
- Modify `PatchFormat`, `PatchRoot`, `PatchScanner`, `PatchDefinition`, and `PatchOperation`: root-aware grammar selection and new fields/operations.
- Modify `PatchEngine`: application context, per-definition result seam, new operations, and effect tracing.
- Modify `PatchGenerationService` and `GenerationPlanFactory`: capture/use one snapshot, expand/bind targets, and publish dependencies.
- Modify native authoring files `PatchDefinitionAsset`, `PatchDefinitionAssetCodec`, and `PatchOperationAsset` in the same public slices.
- Create `docs/authoring-kit/neutral/patch-definition.schema.json` and `docs/authoring-kit/neutral/capabilities.json`; leave the format-2 kit unchanged.
- Create neutral valid/invalid fixtures below `runtime/src/test/resources/authoring-kit/neutral`.
- Update focused runtime, discovery, generation, authoring, docs, and changelog tests/files named in each task.

## Shared Interfaces Produced by This Plan

Later plans rely on these exact public records and methods:

```java
public enum PatchLanguage {
    LEGACY_V1(1, false, false),
    STRICT_V2(2, true, true),
    NEUTRAL(0, true, false);

    public int compatibilityVersion();
    public boolean closedStructure();
    public boolean requiresFormatSentinel();
}

public record MutationEffect(
        String target,
        String patchId,
        String sourcePackId,
        String operationId,
        long operationOrder,
        String path,
        Kind kind,
        String valueFingerprint) {
    public enum Kind { WRITE, ARRAY_MEMBERSHIP, ARRAY_ORDER }
    public static final String REMOVED = "removed";
}

public record GenerationDependencyIndex(
        Set<DefinitionDependency> definitions,
        Set<String> expandedTargets,
        Set<String> sourceAssets,
        Set<GlobRoot> globRoots) {
    public record DefinitionDependency(
            String sourcePackId,
            String assetPath,
            Validity validity,
            Set<String> expandedTargets) { }
    public enum Validity { VALID, INVALID }
    public record GlobRoot(String selector, String stablePrefix) { }
}

public record PatchEngine.ApplicationContext(
        String target,
        GenerationAssetSnapshot assets) { }

public PatchEngine.DefinitionResult applyDefinition(
        JsonObject source,
        PatchDefinition definition,
        PatchEngine.ApplicationContext context,
        long firstOperationOrder);

public static List<PatchDefinition> parseAll(
        JsonObject root,
        String sourcePack,
        String sourcePath,
        int sourcePackLoadOrder,
        PatchLanguage language);

public record PatchEngine.DefinitionResult(
        JsonObject patched,
        List<String> applied,
        List<String> skipped,
        List<MutationEffect> effects,
        long nextOperationOrder) { }
```

`PatchEngine.PatchResult` remains the multi-definition convenience result and adds `List<MutationEffect> effects`. Its existing three-argument constructor is retained for source compatibility.

### Task 1: Root-aware neutral language baseline

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/format/PatchLanguage.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/format/PatchFormat.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchRoot.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchScanner.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchDefinition.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchOperation.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchDefinitionAsset.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchDefinitionAssetCodec.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchOperationAsset.java`
- Create: `docs/authoring-kit/neutral/patch-definition.schema.json`
- Create: `docs/authoring-kit/neutral/capabilities.json`
- Create: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNeutralSchemaTest.java`
- Modify: `docs/Patch-Format.md`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Test: `runtime/src/test/java/com/alechilles/patchwork/discovery/PatchScannerTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchDefinitionTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchDefinitionAssetTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNativeAuthoringSchemaTest.java`

**Interfaces:**
- Consumes: `PatchRoot` selected by `PatchScanner.processFile(...)` and existing `PatchFormat.fromRoot(...)` for explicit/legacy parsing.
- Produces: `PatchLanguage`, `PatchRoot.languageFor(JsonObject)`, `PatchDefinition.parseAll(..., PatchLanguage)`, and `PatchOperation.parseHostOperation(..., PatchLanguage)`.

- [ ] **Step 1: Write failing root-profile tests**

Add tests proving neutral missing-format files are strict and sentinel-free, legacy-root missing-format files remain format 1, and explicit format 2 is unchanged:

```java
@Test
void neutralRootUsesClosedSentinelFreeLanguageWhenMarkerIsAbsent() {
    writePatch(neutralRoot, "modern.json", """
            {"Target":"Server/Test/A.json","Operations":[{"Op":"Remove","Path":"/Old"}]}
            """);
    PatchScanner.ScanResult result = scanner.scan(List.of(source), Set.of());
    assertEquals(PatchLanguage.NEUTRAL, result.definitions().getFirst().language());
    assertTrue(result.failures().isEmpty());
}

@Test
void neutralUnknownOperationRejectsWholeDefinitionEvenWhenOptional() {
    writePatch(neutralRoot, "bad.json", """
            {"Target":"Server/Test/A.json","Operations":[{"Op":"FutureOp","Required":false}]}
            """);
    PatchScanner.ScanResult result = scanner.scan(List.of(source), Set.of());
    assertTrue(result.definitions().isEmpty());
    assertEquals(1, result.failures().size());
}
```

Add direct-parser regression assertions that `PatchDefinition.parseAll(root, pack, path, order)` still interprets missing markers as legacy v1 and explicit format 2 still requires `RequireFormat` first.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchScannerTest,PatchDefinitionTest test
```

Expected: neutral marker-free definitions are currently parsed as legacy v1 and unknown optional operations survive structural parsing.

- [ ] **Step 3: Add the grammar profile and root selection**

Implement `PatchLanguage` exactly as declared in Shared Interfaces. Add:

```java
public PatchLanguage languageFor(JsonObject definitionRoot) {
    if (this == NEUTRAL && !definitionRoot.has("FormatVersion")) return PatchLanguage.NEUTRAL;
    return PatchFormat.fromRoot(definitionRoot).version() == PatchFormat.FORMAT_VERSION_2
            ? PatchLanguage.STRICT_V2 : PatchLanguage.LEGACY_V1;
}
```

In `PatchScanner.processFile(...)`, call the new parse overload with `root.languageFor(rootObject)`. Preserve the current direct overload by delegating through explicit marker parsing:

```java
public static List<PatchDefinition> parseAll(
        JsonObject root, String sourcePack, String sourcePath, int sourcePackLoadOrder) {
    return parseAll(root, sourcePack, sourcePath, sourcePackLoadOrder,
            PatchFormat.fromRoot(root).isVersion2()
                    ? PatchLanguage.STRICT_V2 : PatchLanguage.LEGACY_V1);
}
```

Store `PatchLanguage language` on `PatchDefinition` and `PatchOperation`. Keep `formatVersion()` returning `language.compatibilityVersion()` for existing embedders.

- [ ] **Step 4: Enforce neutral closed structure without a sentinel**

Add a neutral root allowlist of `Id`, `Target`, `Targets`, `Priority`, `Enabled`, `When`, and `Operations`. Reject `FormatVersion`, `RequireFormat`, unknown root fields, unknown operation fields, and unknown operation names structurally before `Required` is consulted.

Use the current strict format-2 field validators for shared operations, but branch by `PatchLanguage.closedStructure()` rather than the integer version. Call `validateSentinel(...)` only when `language.requiresFormatSentinel()`.

```java
if (language.closedStructure()) validateClosedOperation(object, operation, patchId, index, language);
if (language == PatchLanguage.NEUTRAL && "RequireFormat".equalsIgnoreCase(operation)) {
    throw structural(patchId, index, "RequireFormat is not part of neutral definitions.");
}
```

- [ ] **Step 5: Make new Asset Editor definitions neutral by default**

Use `PatchDefinition.parseAll(..., PatchLanguage.NEUTRAL)` in `PatchDefinitionAsset.validatePortableDefinition(...)`, `PatchDefinitionAssetCodec.validateDocument(...)`, and `PatchDefinitionAssetCodec.decodePortableJson(...)` when the portable root has no `FormatVersion`; use the existing explicit parser when it does. Put this selection in one package-private helper so BSON decode, JSON decode, builder validation, and scanner validation cannot disagree.

Remove `FormatVersion` from `PatchDefinitionAssetCodec.PORTABLE_FIELDS` so it is hidden only from the generated root schema, and remove `RequireFormat` from the generated `Op` choices for new authoring. Keep raw decode fields and `portableSource` handling so accepted explicit format 1/2 files reopen and save unchanged. Update root/operation help to say compatibility fields are preserved but are not needed for new files.

```java
PatchLanguage language = root.has("FormatVersion")
        ? PatchFormat.fromRoot(root).isVersion2() ? PatchLanguage.STRICT_V2 : PatchLanguage.LEGACY_V1
        : PatchLanguage.NEUTRAL;
PatchDefinition.parseAll(root, "native-asset-store", sourcePath, 0, language);
```

- [ ] **Step 6: Run parser and native schema tests**

Before running tests, create the closed neutral JSON Schema/capabilities baseline with the existing neutral operations, no `FormatVersion` requirement, and no `RequireFormat` operation. Add valid marker-free and invalid unknown-field/unknown-op fixtures, then document the new authoring baseline and installation-error behavior in `docs/Patch-Format.md`, `README.md`, and `CHANGELOG.md`.

```json
{
  "profile": "neutral",
  "versionFieldRequired": false,
  "operations": ["Add", "Merge", "Replace", "Remove", "Insert", "ReplaceMatching", "RemoveMatching", "MoveMatching", "Macro"]
}
```

Run:

```bash
./mvnw.cmd -pl runtime -Dtest=PatchScannerTest,PatchDefinitionTest,PatchDefinitionAssetTest,PatchNativeAuthoringSchemaTest test
```

Expected: PASS; new native schema does not suggest format fields, while explicit versioned round trips remain exact.

- [ ] **Step 7: Commit the neutral baseline**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/format \
  runtime/src/main/java/com/alechilles/patchwork/discovery/PatchRoot.java \
  runtime/src/main/java/com/alechilles/patchwork/discovery/PatchScanner.java \
  runtime/src/main/java/com/alechilles/patchwork/engine/PatchDefinition.java \
  runtime/src/main/java/com/alechilles/patchwork/engine/PatchOperation.java \
  runtime/src/main/java/com/alechilles/patchwork/authoring \
  docs/authoring-kit/neutral docs/Patch-Format.md README.md CHANGELOG.md \
  runtime/src/test/java/com/alechilles/patchwork/discovery/PatchScannerTest.java \
  runtime/src/test/java/com/alechilles/patchwork/engine/PatchDefinitionTest.java \
  runtime/src/test/java/com/alechilles/patchwork/authoring \
  runtime/src/test/resources/authoring-kit/neutral
git commit -m "Feat: add neutral patch language"
```

### Task 2: Immutable original-asset snapshot

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/generation/GenerationAssetSnapshot.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchScanner.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/generation/PatchGenerationService.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/embedded/GenerationPlanFactory.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/conditions/ConditionSourceResolver.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/generation/GenerationAssetSnapshotTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/generation/PatchGenerationServiceTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/embedded/GenerationPlanFactoryTest.java`

**Interfaces:**
- Consumes: one `HytaleRuntimeInputsSnapshotter.Inputs.sources()` capture and the current secure directory/archive resolution rules.
- Produces: immutable `GenerationAssetSnapshot`, snapshot-backed scanner/condition lookup, and `GenerationRequest.assetSnapshot()`.

- [ ] **Step 1: Write failing snapshot tests**

```java
@Test
void snapshotKeepsOriginalWinnerAfterBackingFilesChange() throws Exception {
    Files.writeString(low.resolve("Server/Test/A.json"), "{\"v\":1}");
    Files.writeString(high.resolve("Server/Test/A.json"), "{\"v\":2}");
    GenerationAssetSnapshot snapshot = GenerationAssetSnapshot.capture(List.of(
            PatchSource.directory("Low", 1, low), PatchSource.directory("High", 2, high)));

    Files.writeString(high.resolve("Server/Test/A.json"), "{\"v\":3}");

    assertEquals("High", snapshot.require("Server/Test/A.json").sourcePackId());
    assertEquals("{\"v\":2}", new String(snapshot.require("Server/Test/A.json").bytes(), UTF_8));
}

@Test
void snapshotExcludesGeneratedPackAndOrdersPathsByUnsignedUtf8() {
    assertFalse(snapshot.sourcePackIds().contains(PatchScanner.GENERATED_PACK_ID));
    assertEquals(snapshot.paths().stream().sorted(Utf8Ordering.UNSIGNED_BYTES).toList(), snapshot.paths());
}
```

- [ ] **Step 2: Run the snapshot test and verify RED**

```bash
./mvnw.cmd -pl runtime -Dtest=GenerationAssetSnapshotTest test
```

Expected: FAIL because `GenerationAssetSnapshot` does not exist.

- [ ] **Step 3: Implement immutable capture and lookup**

Implement:

```java
public final class GenerationAssetSnapshot {
    public record AssetRecord(String sourcePackId, int sourcePackLoadOrder, String path, byte[] bytes) {
        public AssetRecord { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public static GenerationAssetSnapshot capture(List<PatchSource> sources);
    public List<String> paths();
    public List<String> sourcePackIds();
    public Optional<AssetRecord> find(String normalizedPath);
    public AssetRecord require(String normalizedPath);
    public List<String> definitionPaths(PatchRoot root);
}
```

Walk directory sources without following escaping links and enumerate archive entries once. Apply the same winner comparator as `PatchTargetResolver`: higher load order wins, then unsigned UTF-8 pack ID. Clone every byte array on construction and access.

- [ ] **Step 4: Route one generation pass through the snapshot**

Change `GenerationRequest` to carry `GenerationAssetSnapshot assetSnapshot` and remove repeated target filesystem reads from `PatchGenerationService`. Add a compatibility constructor that captures from the existing `sources` argument for direct callers.

`GenerationPlanFactory.createPlan()` must capture exactly once:

```java
GenerationAssetSnapshot assets = GenerationAssetSnapshot.capture(snapshot.sources());
ConditionSourceResolver resolver = resolvers.apply(snapshot.modDataRoots(), cache).withAssets(assets);
return new PatchGenerationService(macros).generate(new GenerationRequest(
        assets, snapshot.installedIds(), snapshot.versions(), version, resolver));
```

Add a snapshot-backed `PatchScanner.scan(GenerationAssetSnapshot, Set<String>)`; retain the existing source-list method as a compatibility wrapper that captures once. Make asset-based conditions resolve through the same snapshot; ModData remains in its existing per-pass cache.

- [ ] **Step 5: Verify single-capture behavior and existing winner tests**

```bash
./mvnw.cmd -pl runtime -Dtest=GenerationAssetSnapshotTest,PatchTargetResolverTest,PatchGenerationServiceTest,GenerationPlanFactoryTest test
```

Expected: PASS; mutation of a backing source during the pass cannot change definitions, targets, imports, or asset conditions for that pass.

- [ ] **Step 6: Commit the snapshot slice**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/generation/GenerationAssetSnapshot.java \
  runtime/src/main/java/com/alechilles/patchwork/discovery/PatchScanner.java \
  runtime/src/main/java/com/alechilles/patchwork/generation/PatchGenerationService.java \
  runtime/src/main/java/com/alechilles/patchwork/embedded/GenerationPlanFactory.java \
  runtime/src/main/java/com/alechilles/patchwork/conditions/ConditionSourceResolver.java \
  runtime/src/test/java/com/alechilles/patchwork/generation \
  runtime/src/test/java/com/alechilles/patchwork/embedded/GenerationPlanFactoryTest.java
git commit -m "Refactor: snapshot generation asset inputs"
```

### Task 3: Concrete mutation effects

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/engine/MutationEffect.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/engine/JsonValueFingerprint.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchEngine.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchEngineTest.java`

**Interfaces:**
- Consumes: concrete operations after macro expansion and a bound target in `PatchEngine.ApplicationContext`.
- Produces: `MutationEffect`, `DefinitionResult`, and trace-bearing `PatchResult` used by the conflict plan.

- [ ] **Step 1: Write failing trace tests**

```java
@Test
void tracesEverySuccessfulLeafWriteIncludingEqualValues() {
    PatchEngine.PatchResult result = engine.apply(json("{\"A\":1,\"Nested\":{\"B\":2}}"),
            List.of(definition("""
                    [{"Op":"Merge","Path":"","Value":{"A":1,"Nested":{"B":3}}}]
                    """)), context("Server/Test/A.json"));

    assertEquals(List.of("/A", "/Nested/B"),
            result.effects().stream().map(MutationEffect::path).toList());
    assertNotEquals(result.effects().get(0).valueFingerprint(), result.effects().get(1).valueFingerprint());
}

@Test
void skippedOptionalOperationProducesNoEffects() {
    assertTrue(result.effects().isEmpty());
}
```

Add array assertions that insert/remove emit `ARRAY_MEMBERSHIP`, move emits `ARRAY_ORDER`, and array element replacement emits concrete `WRITE` leaf effects.

- [ ] **Step 2: Run the engine tests and verify RED**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchEngineTest test
```

Expected: FAIL because `PatchResult` has no effects.

- [ ] **Step 3: Implement stable value fingerprints**

`JsonValueFingerprint.sha256(JsonElement)` must canonicalize objects by unsigned UTF-8 key order, retain array order and JSON types, normalize numbers by mathematical decimal value, and return lowercase SHA-256 hex. Use `MutationEffect.REMOVED` for removals.

```java
static String sha256(JsonElement value) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    canonical(value, digest);
    return HexFormat.of().formatHex(digest.digest());
}
```

Do not store raw before/after values on `MutationEffect`.

- [ ] **Step 4: Add application context and per-definition application**

Implement the Shared Interfaces signatures. Keep current `apply(JsonObject, List<PatchDefinition>)` by delegating to an isolated context whose target is `""` and whose snapshot is empty. Add:

```java
public PatchResult apply(JsonObject source, List<PatchDefinition> definitions, ApplicationContext context) {
    JsonObject working = source.deepCopy();
    List<MutationEffect> effects = new ArrayList<>();
    long order = 0;
    for (PatchDefinition definition : orderedEnabled(definitions)) {
        DefinitionResult next = applyDefinition(working, definition, context, order);
        working = next.patched();
        effects.addAll(next.effects());
        order = next.nextOperationOrder();
    }
    return new PatchResult(working, applied, skipped, effects);
}
```

`applyDefinition` must deep-copy its source, so the conflict plan can accept or discard one definition candidate without undo logic.

- [ ] **Step 5: Trace concrete successful effects**

Thread an effect collector through each raw operation. Recursively enumerate changed/written leaves for Add/Merge/Replace; trace equal-value writes because they were successfully requested. For object or array removals, trace removed concrete leaves plus array membership where applicable. Use the containing array pointer for membership/order effects.

Increment `operationOrder` once per expanded raw operation; every effect from that raw operation shares the same order. Use `operation.id()` for macro-expanded effects so no editor-visible synthetic ID is introduced.

- [ ] **Step 6: Run all engine tests**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchEngineTest,PatchMacroRegistryTest test
```

Expected: PASS, including source compatibility for existing callers of the three-field `PatchResult` constructor.

- [ ] **Step 7: Commit tracing**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/engine/MutationEffect.java \
  runtime/src/main/java/com/alechilles/patchwork/engine/JsonValueFingerprint.java \
  runtime/src/main/java/com/alechilles/patchwork/engine/PatchEngine.java \
  runtime/src/test/java/com/alechilles/patchwork/engine/PatchEngineTest.java
git commit -m "Feat: trace concrete patch mutations"
```

### Task 4: `MergeMatching` and `UpsertMatching`

**Files:**
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchOperation.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchEngine.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchOperationAsset.java`
- Modify: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchEngineTest.java`
- Modify: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNativeAuthoringSchemaTest.java`
- Create: `runtime/src/test/resources/authoring-kit/neutral/valid/merge-matching.json`
- Create: `runtime/src/test/resources/authoring-kit/neutral/valid/upsert-matching.json`
- Create: `runtime/src/test/resources/authoring-kit/neutral/invalid/upsert-relative-without-find.json`
- Modify: `docs/authoring-kit/neutral/patch-definition.schema.json`
- Modify: `docs/authoring-kit/neutral/capabilities.json`
- Modify: `docs/Operations.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: current recursive `JsonMatcher`, pre-operation array snapshots, `MatchPolicy`, `Position`, `Find`, and mutation collector.
- Produces: neutral-only `MergeMatching` and `UpsertMatching` with native guided fields.

- [ ] **Step 1: Write failing operation tests**

```java
@Test
void mergeMatchingDeepMergesAllSelectedObjectsFromOneSnapshot() {
    JsonObject result = applyNeutral("""
            {"Rows":[{"Id":"a","Data":{"X":1}},{"Id":"b","Data":{"X":2}}]}
            """, """
            {"Op":"MergeMatching","Path":"/Rows","Match":{"Data":{"X":{"$Equals":1}}},
             "MatchPolicy":"All","Value":{"Data":{"Y":3}}}
            """);
    assertEquals(json("{\"X\":1,\"Y\":3}"), result.getAsJsonArray("Rows").get(0).getAsJsonObject().get("Data"));
}

@Test
void upsertMatchingInsertsOneValueWhenAllPolicyMatchesNothing() {
    JsonObject result = applyNeutral("{\"Rows\":[]}", """
            {"Op":"UpsertMatching","Path":"/Rows","Match":{"Id":"a"},
             "MatchPolicy":"All","Value":{"Id":"a","Enabled":true}}
            """);
    assertEquals(1, result.getAsJsonArray("Rows").size());
}
```

Cover zero/one/multiple matches for each policy, non-object selected values, object-only `Value`, Start/End/Before/After insertion, first-match `Find`, missing anchor, and optional applicability.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchEngineTest,PatchNativeAuthoringSchemaTest test
```

Expected: FAIL because the operations and editor enum choices do not exist.

- [ ] **Step 3: Add strict operation shapes**

Add neutral closed allowlists:

```java
case "MergeMatching" -> fields(
        Set.of("Op", "Path", "Match", "Value", "Id", "Required", "MatchPolicy"),
        Set.of("Op", "Path", "Match", "Value"));
case "UpsertMatching" -> fields(
        Set.of("Op", "Path", "Match", "Value", "Id", "Required", "MatchPolicy", "Position", "Find"),
        Set.of("Op", "Path", "Match", "Value"));
```

Require `Value` to be an object structurally. Validate Match/Find with neutral strict matcher rules. Allow `Find` only for Before/After; require it there.

- [ ] **Step 4: Implement matching merge/upsert and effects**

Factor match selection into a result that permits zero only for upsert:

```java
private static List<Integer> matchingIndexes(
        JsonArray snapshot, JsonObject matcher, String policy,
        PatchOperation operation, boolean allowZero);
```

`MergeMatching` rejects zero under normal applicability. `UpsertMatching` deep-merges selected object entries when nonempty; on zero it inserts exactly one deep copy of `Value`, including with policy All. Resolve `Find` against the pre-operation snapshot and choose its first match, matching existing `Insert.Find` semantics.

Trace merged leaves as `WRITE`; insertion as concrete writes plus `ARRAY_MEMBERSHIP`.

- [ ] **Step 5: Add guided native authoring**

Add both `OperationType` choices with nonblank descriptions. Reuse `PatchMatcherCodec` for Match/Find, enum codecs for MatchPolicy/Position, and `PatchJsonObjectCodec` for these operations' Value schema. If Hytale cannot render per-Op alternatives, keep the shared fields but make each tooltip state exactly which operations use it.

Assert in `PatchNativeAuthoringSchemaTest` that both choices are selectable, documented, recursively typed, and expose Path/Match/Value plus only the applicable optional controls.

- [ ] **Step 6: Run focused runtime and authoring tests**

Before running tests, add both operation variants to the neutral JSON Schema/capabilities and document their merge/upsert semantics and editor workflow in `docs/Operations.md` and `CHANGELOG.md`.

```bash
./mvnw.cmd -pl runtime -Dtest=PatchEngineTest,PatchDefinitionTest,PatchNativeAuthoringSchemaTest,PatchDefinitionAssetTest test
```

Expected: PASS.

- [ ] **Step 7: Commit the matching operations**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/engine \
  runtime/src/main/java/com/alechilles/patchwork/authoring/PatchOperationAsset.java \
  runtime/src/test/java/com/alechilles/patchwork/engine \
  runtime/src/test/java/com/alechilles/patchwork/authoring \
  runtime/src/test/resources/authoring-kit/neutral \
  docs/authoring-kit/neutral docs/Operations.md CHANGELOG.md
git commit -m "Feat: add matching merge and upsert"
```

### Task 5: Exact-path cross-asset merge operations

**Files:**
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchOperation.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchEngine.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchOperationAsset.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchEngineTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/generation/PatchGenerationServiceTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNativeAuthoringSchemaTest.java`
- Create: `runtime/src/test/resources/authoring-kit/neutral/valid/overlay-from-asset.json`
- Create: `runtime/src/test/resources/authoring-kit/neutral/valid/merge-object-from-asset.json`
- Create: `runtime/src/test/resources/authoring-kit/neutral/invalid/source-glob.json`
- Modify: `docs/authoring-kit/neutral/patch-definition.schema.json`
- Modify: `docs/authoring-kit/neutral/capabilities.json`
- Modify: `docs/Operations.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `PatchEngine.ApplicationContext.assets()` and bound `context.target()`.
- Produces: `PatchOperation.source()`, `PatchOperation.sourcePath()`, `OverlayFromAsset`, and `MergeObjectFromAsset`.

- [ ] **Step 1: Write failing source-operation tests**

```java
@Test
void overlayReadsOriginalSnapshotAndLaterOperationsWin() {
    GenerationAssetSnapshot assets = snapshot(Map.of(
            "Server/Test/Source.json", "{\"Shared\":{\"A\":1},\"FromSource\":true}"));
    JsonObject result = applyNeutralWithContext("Server/Test/Target.json",
            "{\"Shared\":{\"B\":2}}", assets,
            op("OverlayFromAsset", "Source", "Server/Test/Source.json"),
            op("Replace", "Path", "/Shared/A", "Value", 9));
    assertEquals(json("{\"A\":9,\"B\":2}"), result.getAsJsonObject("Shared"));
}

@Test
void mergeObjectSelectsSourcePathAndExistingDestination() {
    assertEquals(json("{\"Keep\":1,\"Imported\":2}"), merged.getAsJsonObject("Destination"));
}
```

Cover missing source, missing SourcePath, non-object source/destination, `Required:false`, generated-pack exclusion, and self-overlay rejection.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchEngineTest,PatchGenerationServiceTest,PatchNativeAuthoringSchemaTest test
```

Expected: FAIL because source fields and operations are unsupported.

- [ ] **Step 3: Add operation fields and closed shapes**

Extend `PatchOperation` with nullable `source` and `sourcePath`, including getters and `toJson()`. Neutral allowlists are:

```java
case "OverlayFromAsset" -> fields(
        Set.of("Op", "Source", "Id", "Required"), Set.of("Op", "Source"));
case "MergeObjectFromAsset" -> fields(
        Set.of("Op", "Source", "SourcePath", "Path", "Id", "Required"),
        Set.of("Op", "Source", "Path"));
```

Normalize `Source` as an exact safe asset path and reject any `glob:` prefix. Parse `SourcePath` as an RFC 6901 pointer using neutral strict rules; null means root.

- [ ] **Step 4: Implement snapshot-only source merging**

Resolve `Source` only through `context.assets().find(source)`. Parse its captured bytes as JSON and require an object root. `OverlayFromAsset` rejects `source.equals(context.target())` and deep-merges the source object into the working root. `MergeObjectFromAsset` resolves `SourcePath` in the source object and `Path` in the current working target; both selected values must be objects.

Missing assets/paths and type mismatches throw ordinary applicability exceptions so `Required:false` skips. Structural field errors still reject parsing. Trace every successfully written target leaf, including equal-value writes.

- [ ] **Step 5: Add the two native operation cards**

Add operation labels and descriptions exactly:

- `OverlayFromAsset`: **Overlay entire asset** — source leaves win; unrelated target fields remain; exact original source only.
- `MergeObjectFromAsset`: **Merge object from asset** — `SourcePath` defaults to source root; `Path` must already be an object.

Add `Source` and `SourcePath` to `PatchOperationAsset`, its codec allowlist, portable decode/encode, and lossless tests. Give every field nonblank help for merge direction, defaults, exact-path restriction, and optional applicability.

- [ ] **Step 6: Run operation, generation, and authoring tests**

Before running tests, add both exact-source operation variants to the neutral JSON Schema/capabilities and document the approved UI labels, merge direction, snapshot isolation, defaults, and applicability failures in `docs/Operations.md` and `CHANGELOG.md`.

```bash
./mvnw.cmd -pl runtime -Dtest=PatchEngineTest,PatchGenerationServiceTest,PatchNativeAuthoringSchemaTest,PatchDefinitionAssetTest test
```

Expected: PASS.

- [ ] **Step 7: Commit the cross-asset operations**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/engine \
  runtime/src/main/java/com/alechilles/patchwork/authoring/PatchOperationAsset.java \
  runtime/src/test/java/com/alechilles/patchwork \
  runtime/src/test/resources/authoring-kit/neutral \
  docs/authoring-kit/neutral docs/Operations.md CHANGELOG.md
git commit -m "Feat: add cross-asset merge operations"
```

### Task 6: Explicit glob targets and dependency index

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchTargetSelector.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchTargetExpander.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/generation/GenerationDependencyIndex.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchDefinition.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchScanner.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/generation/PatchGenerationService.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchDefinitionAsset.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/discovery/PatchTargetExpanderTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/generation/PatchGenerationServiceTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNativeAuthoringSchemaTest.java`
- Create: `runtime/src/test/resources/authoring-kit/neutral/valid/glob-targets.json`
- Create: `runtime/src/test/resources/authoring-kit/neutral/invalid/raw-wildcard-target.json`
- Modify: `docs/authoring-kit/neutral/patch-definition.schema.json`
- Modify: `docs/authoring-kit/neutral/capabilities.json`
- Modify: `docs/Patch-Format.md`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `GenerationAssetSnapshot.paths()` and parsed definition source identity.
- Produces: `PatchTargetSelector`, `PatchDefinition.bindTarget(String)`, `PatchTargetExpander.expand(...)`, `GenerationDependencyIndex`, and `GenerationPlan.dependencies()`.

- [ ] **Step 1: Write failing selector/expansion tests**

```java
@Test
void expandsExplicitGlobDeduplicatesAndOrdersUnsignedUtf8() {
    List<String> result = expander.expand(List.of(
            PatchTargetSelector.parse("glob:Server/NPC/**/*.json"),
            PatchTargetSelector.parse("Server/NPC/Wolf.json")), snapshot);
    assertEquals(List.of("Server/NPC/Bear.json", "Server/NPC/Wolf.json"), result);
}

@Test
void starDoesNotCrossSegmentsAndQuestionMatchesOneCharacter() {
    assertTrue(PatchTargetSelector.parse("glob:Server/Item/?.json").matches("Server/Item/A.json"));
    assertFalse(PatchTargetSelector.parse("glob:Server/Item/*.json").matches("Server/Item/Sub/A.json"));
}
```

Add zero-match warning, empty prior expansion dependency, exact/glob dedupe, generated exclusion, and unsafe selector tests.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchTargetExpanderTest,PatchGenerationServiceTest test
```

Expected: FAIL because selectors and dependencies do not exist.

- [ ] **Step 3: Implement selector parsing without regex exposure**

`PatchTargetSelector.parse(String)` returns `Kind.EXACT` unless the string begins exactly with `glob:`. Compile only `*`, `**`, and `?` internally after normalizing slash-separated safe segments. Reject raw `*` or `?` in exact paths and reject regex syntax as ordinary literal characters rather than executing it.

```java
public record PatchTargetSelector(Kind kind, String expression, String stablePrefix) {
    public enum Kind { EXACT, GLOB }
    public static PatchTargetSelector parse(String text);
    public boolean matches(String normalizedAssetPath);
}
```

`stablePrefix` is the slash-delimited literal prefix before the first wildcard; it may be empty.

- [ ] **Step 4: Bind expanded definitions before grouping**

Store the parsed selector on `PatchDefinition`. Exact definitions are bound immediately; glob definitions require `bindTarget(concreteTarget)`, which returns a copy preserving ID, operations, source identity, language, priority, and condition.

In `PatchGenerationService.generate(...)`, expand every scanned definition against the snapshot, deduplicate concrete `(sourcePack, id, target)` keys, sort targets with `Utf8Ordering.UNSIGNED_BYTES`, then execute the existing target-local grouping flow. Zero matches add a warning to `PatchStatusSnapshot.skipped()` and no target.

- [ ] **Step 5: Build immutable dependencies into the plan**

Construct `GenerationDependencyIndex` exactly as declared in Shared Interfaces:

- every scanned source file contributes `DefinitionDependency`; successful, disabled, and shadowed files use `Validity.VALID`, while parse/read failures use `Validity.INVALID`; successful definitions retain their expanded targets and invalid files retain an empty current set, so publication can distinguish invalid edits from deleted files;
- every concrete expansion contributes `expandedTargets`;
- every `OverlayFromAsset`/`MergeObjectFromAsset` contributes its exact `Source`;
- every glob contributes `(selector, stablePrefix)`, including zero-match globs.

Add `GenerationDependencyIndex dependencies` to `GenerationPlan`, with compatibility constructors defaulting to `GenerationDependencyIndex.empty()`.

- [ ] **Step 6: Make Target/Targets self-explanatory in the Asset Editor**

Update native help with examples `Server/Item/Items/Example.json` and `glob:Server/NPC/**/*.json`. State that only prefixed globs are patterns, `*` stays within one segment, `**` crosses segments, `?` matches one character, and source-operation fields never accept globs.

Assert the real generated schema has nonblank help and the neutral JSON Schema applies a closed exact-or-glob string pattern.

- [ ] **Step 7: Run focused generation/authoring tests**

Before running tests, add exact-or-`glob:` target constraints and token capabilities to the neutral authoring kit and document examples/zero-match warnings in `docs/Patch-Format.md`, `README.md`, and `CHANGELOG.md`.

```bash
./mvnw.cmd -pl runtime -Dtest=PatchTargetExpanderTest,PatchScannerTest,PatchGenerationServiceTest,GenerationPlanFactoryTest,PatchNativeAuthoringSchemaTest test
```

Expected: PASS.

- [ ] **Step 8: Commit target expansion and dependencies**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/discovery \
  runtime/src/main/java/com/alechilles/patchwork/generation \
  runtime/src/main/java/com/alechilles/patchwork/engine/PatchDefinition.java \
  runtime/src/main/java/com/alechilles/patchwork/authoring/PatchDefinitionAsset.java \
  runtime/src/test/java/com/alechilles/patchwork \
  runtime/src/test/resources/authoring-kit/neutral \
  docs/authoring-kit/neutral docs/Patch-Format.md README.md CHANGELOG.md
git commit -m "Feat: expand explicit patch target globs"
```

### Task 7: Cross-feature neutral contract audit

**Files:**
- Modify: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNeutralSchemaTest.java`
- Modify: `docs/Patch-Format.md`
- Modify: `docs/Operations.md`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: the same-slice authoring artifacts from Tasks 1, 4, 5, and 6.
- Produces: one cross-feature parity fixture and overview documentation; the existing `docs/authoring-kit/v2` remains byte-for-byte semantically format 2.

- [ ] **Step 1: Write the neutral schema corpus test**

```java
@Test
void neutralSchemaAcceptsValidCorpusAndRejectsInvalidCorpus() throws Exception {
    JsonSchema schema = load("docs/authoring-kit/neutral/patch-definition.schema.json");
    assertCorpus(schema, "runtime/src/test/resources/authoring-kit/neutral/valid", true);
    assertCorpus(schema, "runtime/src/test/resources/authoring-kit/neutral/invalid", false);
}

@Test
void formatTwoSchemaStillHasOriginalSentinelContract() {
    assertTrue(validateV2("valid/replace-matching.json").isEmpty());
    assertFalse(validateV2("invalid/missing-require-format.json").isEmpty());
}
```

- [ ] **Step 2: Run the schema tests and verify RED**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchNeutralSchemaTest,PatchDefinitionSchemaTest test
```

Expected: PASS for the per-feature corpus before the cross-feature fixture is added.

- [ ] **Step 3: Add one cross-feature contract fixture**

Add a valid neutral fixture combining glob Targets, `MergeMatching`, `UpsertMatching`, `OverlayFromAsset`, and `MergeObjectFromAsset`. Assert the portable schema accepts it, the runtime parser produces the expected selectors/fields, and the native generated schema exposes every referenced operation and field with nonblank documentation.

Also assert `capabilities.json` identifies profile `neutral`, declares `versionFieldRequired: false`, enumerates exact operation names, and includes glob tokens `*`, `**`, `?`. Do not add these operations to the format-2 capability file.

- [ ] **Step 4: Document author intent and installation compatibility**

Update user docs with complete JSON examples for all four operations and glob targets. Explain that new neutral authors never enter a format, consuming mods declare a sufficiently new Patchwork dependency, unknown neutral syntax fails closed, and explicit/legacy definitions retain their documented rules.

Update `CHANGELOG.md` under an unreleased section and describe only behavior implemented by this completed plan.

- [ ] **Step 5: Run the full runtime test suite**

```bash
./mvnw.cmd -pl runtime test
```

Expected: PASS.

- [ ] **Step 6: Run repository-wide verification**

```bash
./mvnw.cmd test
```

Expected: PASS across runtime and standalone modules.

- [ ] **Step 7: Commit contract and docs**

```bash
git add docs/authoring-kit/neutral docs/Patch-Format.md docs/Operations.md README.md CHANGELOG.md \
  runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNeutralSchemaTest.java
git commit -m "Docs: publish neutral patch language contract"
```

## Plan Completion Gate

Before starting the conflict plan, verify:

```bash
./mvnw.cmd test
git status --short
```

Expected: all tests pass and the worktree contains no uncommitted implementation changes. Confirm that `GenerationPlan.dependencies()`, `PatchEngine.DefinitionResult`, and `MutationEffect` exactly match Shared Interfaces; the next plans compile against those names.
