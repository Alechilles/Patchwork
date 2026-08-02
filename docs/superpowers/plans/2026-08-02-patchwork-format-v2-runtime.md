# Patchwork Format 2 Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Patchwork format version 2 support: strict portable parsing, deterministic ordering, `TargetProvidedBy`, and matcher-based array replacement, removal, and movement without regressing format version 1 definitions.

**Architecture:** Keep the existing scanner → target resolver → condition evaluator → engine pipeline. Introduce a small format-semantics layer that owns format detection, strict JSON/pointer/matcher behavior, and unsigned UTF-8 ordering; `PatchDefinition` and `PatchOperation` carry the selected semantics into the engine. Format 2 structural errors occur during discovery, while target-specific applicability failures keep the existing `Required` behavior.

**Tech Stack:** Java 25, Maven, JUnit Jupiter, Gson, existing standalone and embedded Patchwork modules.

## Global Constraints

- Preserve format version 1 parsing, pointer, matcher, and operation behavior for definitions without `FormatVersion`.
- A format-2 document must contain exactly one first `{ "Op": "RequireFormat", "Version": 2 }` operation; version-1 runtimes therefore reject it as their existing unsupported required operation.
- Reject all format-2 structural defects before any target is applied, even when an operation has `Required: false`.
- Use RFC 6901 pointer validation for format 2; legacy definitions retain the current pointer behavior.
- Compare patch IDs and source-pack IDs with unsigned UTF-8 byte ordering, with no Unicode normalization.
- Use the already resolved target snapshot and provider identity for conditions; do not resolve the winning target again.
- Use Git Bash for repository commands, Java 25, `./mvnw.cmd test` after source changes, and update `docs/Patch-Format.md`, `docs/Operations.md`, and `CHANGELOG.md` with user-facing behavior.

---

### Task 1: Add format-2 parsing, structural validation, and the compatibility sentinel

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/format/PatchFormat.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/format/PatchDefinitionReader.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchDefinition.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchOperation.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchScanner.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchEngine.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchDefinitionTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/discovery/PatchScannerTest.java`

**Interfaces:**
- Produces `PatchFormat` with `LEGACY_VERSION = 1`, `FORMAT_VERSION_2 = 2`, `fromRoot(JsonObject)`, and format-aware field/pointer checks.
- Produces `PatchDefinitionReader.parse(byte[], sourcePack, sourcePath, sourcePackLoadOrder)`, which rejects duplicate JSON keys for format-2 input before model parsing.
- Extends `PatchDefinition` with `int formatVersion()` and `PatchOperation` with `int formatVersion()`, `Integer version()`, `JsonObject match()`, and `String matchPolicy()`.
- `PatchScanner.processFile` calls the reader instead of calling Gson directly.

- [ ] **Step 1: Write failing format-2 definition tests**

Add focused tests that expect `PatchDefinition.parseAll` to reject each structural form below:

```java
assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parseAll(object("""
  { "FormatVersion": 2, "Id": "v2", "Target": "Server/A.json", "Operations": [] }
  """), "pack", "patch.json"));

assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parseAll(object("""
  { "FormatVersion": 2, "Id": "v2", "Target": "Server/A.json", "Operations": [
    { "Op": "RequireFormat", "Version": 2, "Required": false }
  ] }
  """), "pack", "patch.json"));
```

Add scanner tests for a valid sentinel and for duplicate keys in a format-2 definition byte stream:

```java
write(source, "Server/Patchwork/Patches/v2.json", """
  {"FormatVersion":2,"Id":"v2","Id":"duplicate","Target":"Server/A.json",
   "Operations":[{"Op":"RequireFormat","Version":2}]}
  """);
assertEquals(1, scanner.scan(List.of(PatchSource.directory("pack", 1, source)), Set.of()).failures().size());
```

- [ ] **Step 2: Run the focused tests and verify they fail for missing version-2 behavior**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchDefinitionTest,PatchScannerTest test`

Expected: failures showing that a missing sentinel, forbidden sentinel `Required`, and duplicate keys are currently accepted.

- [ ] **Step 3: Implement format detection and byte-level definition reading**

Create `PatchFormat` with constants, a positive-integer `FormatVersion` reader, and `isVersion2(int)`. Create `PatchDefinitionReader` that decodes bytes with a UTF-8 decoder configured with malformed and unmappable input reporting, reads JSON recursively while recording every object key, then rejects duplicates when the parsed root declares version 2. Return a `JsonObject` only after checking the root is an object.

Keep `PatchDefinition.parseAll(JsonObject, ...)` public for in-memory callers. It must read `FormatVersion`, reject unsupported versions, and, for version 2, reject unknown root fields, require the sentinel as operation index zero, require the sentinel version to equal the root version, and reject a second sentinel.

- [ ] **Step 4: Add format-aware operation models and sentinel dispatch**

Extend `PatchOperation.parse` to receive the definition version and retain `Version`, `Match`, and `MatchPolicy` values. For version 2, validate the allowed/required/forbidden field matrix for `RequireFormat`, `ReplaceMatching`, `RemoveMatching`, and `MoveMatching` before creating the operation. The sentinel accepts only `Op`, `Version`, and optional `Id`; it has no `Required` property. In `PatchEngine.raw`, recognize `RequireFormat` as an applied no-op only when its version equals the definition format version.

- [ ] **Step 5: Route scanner discovery through the byte-level reader**

Replace the direct `JsonParser.parseString(new String(...))` call in `PatchScanner.processFile` with `PatchDefinitionReader.parse(...)`, then preserve the existing file-level exception handling so one malformed definition becomes one scan failure and no expanded targets from that file are accepted.

- [ ] **Step 6: Run the focused tests and verify they pass**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchDefinitionTest,PatchScannerTest test`

Expected: PASS, with legacy fixtures still parsing and all format-2 structural cases rejected at parse/discovery time.

- [ ] **Step 7: Commit the parsing slice**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/format/PatchFormat.java \
  runtime/src/main/java/com/alechilles/patchwork/format/PatchDefinitionReader.java \
  runtime/src/main/java/com/alechilles/patchwork/engine/PatchDefinition.java \
  runtime/src/main/java/com/alechilles/patchwork/engine/PatchOperation.java \
  runtime/src/main/java/com/alechilles/patchwork/discovery/PatchScanner.java \
  runtime/src/main/java/com/alechilles/patchwork/engine/PatchEngine.java \
  runtime/src/test/java/com/alechilles/patchwork/engine/PatchDefinitionTest.java \
  runtime/src/test/java/com/alechilles/patchwork/discovery/PatchScannerTest.java
git commit -m "Feat: add Patchwork format 2 parsing"
```

### Task 2: Introduce format-aware JSON pointers and exact matchers

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/format/JsonPointer.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/format/JsonMatcher.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchEngine.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/conditions/PatchCondition.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/conditions/PatchConditionParser.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/conditions/PatchConditionEvaluator.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchEngineTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/conditions/PatchConditionParserTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/conditions/PatchConditionEvaluatorTest.java`

**Interfaces:**
- Produces `JsonPointer.tokens(String, int formatVersion, boolean mutation)` and uses strict RFC 6901 validation only for version 2.
- Produces `JsonMatcher.validateV2(JsonObject)` and `JsonMatcher.matches(JsonElement, JsonObject, int formatVersion)`.
- `PatchEngine` resolves operation pointers using `operation.formatVersion()`; condition pointers use the enclosing definition format version supplied by the parser/evaluator.

- [ ] **Step 1: Write failing matcher and pointer tests**

Add engine tests demonstrating the desired version-2 behavior:

```java
assertTrue(JsonMatcher.matches(JsonParser.parseString("1.0"), object("{ \"$Equals\": 1e0 }"), 2));
assertTrue(JsonMatcher.matches(JsonParser.parseString("[\"a\", \"b\"]"), object("{ \"$Contains\": { \"$Equals\": \"b\" } }"), 2));
assertThrows(IllegalArgumentException.class, () -> JsonMatcher.validateV2(object("{}")));
assertThrows(IllegalArgumentException.class, () -> JsonMatcher.validateV2(object("{ \"$Equals\": 1, \"Id\": \"x\" }")));
```

Add format-2 operation and condition tests that reject `"/a~2b"`, `"/items/01"`, and mutation of the empty document pointer, while a legacy definition retains its existing pointer behavior.

- [ ] **Step 2: Run the focused tests and verify they fail**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchEngineTest,PatchConditionParserTest,PatchConditionEvaluatorTest test`

Expected: failures because current matching accepts empty matchers, treats numbers through Gson equality, and accepts invalid escapes.

- [ ] **Step 3: Implement strict pointer parsing**

Implement `JsonPointer` so format 2 permits the empty pointer only for inspection, verifies that every `~` is followed by `0` or `1`, rejects signed/leading-zero/overflow array indexes, and accepts `-` only as the final `Add` array token. Retain the existing legacy token behavior in a separate legacy branch rather than tightening it globally.

Use this class from the engine and condition evaluator, with condition parsing retaining the format version on parsed JSON-path conditions so it can evaluate the same pointer contract.

- [ ] **Step 4: Implement strict matcher validation and equality**

Implement the three valid version-2 matcher shapes: a sole `$Equals` key, a sole `$Contains` key containing a valid matcher, or a non-empty ordinary object with no reserved `$` keys. Implement exact equality with recursive array/object comparison and `BigDecimal` numeric comparison. Keep the current recursive object/$Contains matcher in a legacy branch and invoke the strict matcher for version-2 `Insert`, `ReplaceMatching`, `RemoveMatching`, and `MoveMatching` fields.

- [ ] **Step 5: Run the focused tests and verify they pass**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchEngineTest,PatchConditionParserTest,PatchConditionEvaluatorTest test`

Expected: PASS, including exact numeric equality, strict reserved-key validation, strict pointers, and unchanged legacy tests.

- [ ] **Step 6: Commit the semantic primitives**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/format/JsonPointer.java \
  runtime/src/main/java/com/alechilles/patchwork/format/JsonMatcher.java \
  runtime/src/main/java/com/alechilles/patchwork/engine/PatchEngine.java \
  runtime/src/main/java/com/alechilles/patchwork/conditions/PatchCondition.java \
  runtime/src/main/java/com/alechilles/patchwork/conditions/PatchConditionParser.java \
  runtime/src/main/java/com/alechilles/patchwork/conditions/PatchConditionEvaluator.java \
  runtime/src/test/java/com/alechilles/patchwork/engine/PatchEngineTest.java \
  runtime/src/test/java/com/alechilles/patchwork/conditions/PatchConditionParserTest.java \
  runtime/src/test/java/com/alechilles/patchwork/conditions/PatchConditionEvaluatorTest.java
git commit -m "Feat: add strict format 2 JSON semantics"
```

### Task 3: Implement matcher-based array operations

**Files:**
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchEngine.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchOperation.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchEngineTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchDefinitionTest.java`

**Interfaces:**
- `ReplaceMatching` and `RemoveMatching` accept `MatchPolicy` values `ExactlyOne`, `First`, `Last`, and `All`; default is `ExactlyOne`.
- `MoveMatching` matches exactly one item; `Start`/`End` use no `Find`, while `Before`/`After` use exactly one distinct `Find` anchor.
- All selection happens against one immutable pre-operation array snapshot.

- [ ] **Step 1: Write failing operation behavior tests**

Add independent tests for replacement, removal, movement, and diagnostics:

```java
PatchEngine.PatchResult result = engine.apply(object("""
  { "items": [{"id":"a"},{"id":"b"},{"id":"b"},{"id":"c"}] }
  """), List.of(v2Definition("""
  { "Op":"ReplaceMatching", "Path":"/items", "Match":{"id":"b"},
    "MatchPolicy":"All", "Value":{"id":"replaced"} }
  """)));
assertEquals(List.of("a", "replaced", "replaced", "c"), objectIds(result.patched(), "items"));
```

Cover zero-match, ambiguous exactly-one, first/last/all selection, non-array path, remove high-to-low behavior, move before/after with moving item on both sides of anchor, self-anchor rejection, and the exact skipped diagnostic `already in requested position`.

- [ ] **Step 2: Run the focused engine tests and verify they fail**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchEngineTest,PatchDefinitionTest test`

Expected: failures for unsupported matcher operations and missing structural policy validation.

- [ ] **Step 3: Implement selection and replacement/removal**

Add an array snapshot helper that computes all matching indexes before mutation. Validate and normalize `MatchPolicy`, reject zero matches for every policy, reject multiple matches under `ExactlyOne`, and return selected indexes for `First`, `Last`, or `All`. Replace each selected index with a deep copy of `Value`; remove selected indexes from greatest to least.

- [ ] **Step 4: Implement move semantics**

Select the moving item and anchor against the same snapshot. Remove the moving item, adjust the anchor index by one when the moving index preceded it, then insert before/after the adjusted anchor. For `Start` and `End`, insert at zero or the new array length. Detect a self-anchor before removal. If the final list is JSON-equal to the original list, return the exact skip reason `already in requested position` without treating the operation as failed.

- [ ] **Step 5: Run the focused engine tests and verify they pass**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchEngineTest,PatchDefinitionTest test`

Expected: PASS for every match policy and move-index case, with required versus optional applicability failures continuing to use `PatchFailureException`/skipped diagnostics.

- [ ] **Step 6: Commit the operation slice**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/engine/PatchEngine.java \
  runtime/src/main/java/com/alechilles/patchwork/engine/PatchOperation.java \
  runtime/src/test/java/com/alechilles/patchwork/engine/PatchEngineTest.java \
  runtime/src/test/java/com/alechilles/patchwork/engine/PatchDefinitionTest.java
git commit -m "Feat: add matcher array operations"
```

### Task 4: Carry the winning provider snapshot into `TargetProvidedBy`

**Files:**
- Modify: `runtime/src/main/java/com/alechilles/patchwork/conditions/PatchCondition.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/conditions/PatchConditionParser.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/conditions/PatchConditionEvaluator.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/generation/PatchGenerationService.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/conditions/PatchConditionParserTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/conditions/PatchConditionEvaluatorTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/generation/PatchGenerationServiceTest.java`

**Interfaces:**
- Produces `PatchCondition.TargetProvidedBy(String sourcePackId)`.
- Extends `PatchConditionEvaluator.EvaluationContext` with `String targetSourcePackId` while preserving existing constructors for callers that do not know a provider.
- Changes `PatchGenerationService.EvaluateStage` to receive `PatchTargetResolver.ResolvedTarget`, not just target bytes.

- [ ] **Step 1: Write failing condition and generation tests**

Add parser/evaluator tests:

```java
PatchCondition condition = new PatchConditionParser().parse(object("{ \"TargetProvidedBy\": \"Example:Dragons\" }"));
PatchConditionEvaluator.EvaluationContext context = contextWithProvider("Example:Dragons");
assertTrue(new PatchConditionEvaluator().evaluate(condition, context).matched());
assertEquals(PatchConditionEvaluator.Status.NOT_MATCHED,
    new PatchConditionEvaluator().evaluate(condition, contextWithProvider("example:dragons")).status());
```

Add a `PatchGenerationServiceTest` with a resolver stage returning `new ResolvedTarget("Example:Dragons", 4, target, bytes)` and an evaluator stage that asserts it receives that exact record instance. Also preserve the existing scan → resolve → condition → apply ordering assertion.

- [ ] **Step 2: Run the focused condition and generation tests and verify they fail**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchConditionParserTest,PatchConditionEvaluatorTest,PatchGenerationServiceTest test`

Expected: compile or assertion failures because provider identity is not represented or passed through the pipeline.

- [ ] **Step 3: Implement condition parsing and evaluation**

Add the sealed condition record and parse the exact-cased `TargetProvidedBy` key as a non-empty string. In the evaluator, return `MATCHED` only when the supplied provider string equals the condition source-pack ID; return `NOT_MATCHED` for a different or unavailable provider. Do not call `PatchTargetResolver` from this branch.

- [ ] **Step 4: Pass the already-resolved target record through generation**

Change `planTarget` and `eligibleDefinitions` to retain `resolved.resolvedTarget()` and supply its `sourcePackId()` and defensively copied `bytes()` to the condition context. Update the package-private functional interface and its tests together so injected stages see one resolution and cannot accidentally trigger a second winning-provider lookup.

- [ ] **Step 5: Run the focused tests and verify they pass**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchConditionParserTest,PatchConditionEvaluatorTest,PatchGenerationServiceTest test`

Expected: PASS for exact-case matching, mismatch skip behavior, and one resolved snapshot passed to condition evaluation.

- [ ] **Step 6: Commit the provider condition slice**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/conditions/PatchCondition.java \
  runtime/src/main/java/com/alechilles/patchwork/conditions/PatchConditionParser.java \
  runtime/src/main/java/com/alechilles/patchwork/conditions/PatchConditionEvaluator.java \
  runtime/src/main/java/com/alechilles/patchwork/generation/PatchGenerationService.java \
  runtime/src/test/java/com/alechilles/patchwork/conditions/PatchConditionParserTest.java \
  runtime/src/test/java/com/alechilles/patchwork/conditions/PatchConditionEvaluatorTest.java \
  runtime/src/test/java/com/alechilles/patchwork/generation/PatchGenerationServiceTest.java
git commit -m "Feat: add target provider condition"
```

### Task 5: Make source and definition ordering portable and total

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/format/Utf8Ordering.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchDefinition.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchScanner.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchTargetResolver.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/generation/PatchGenerationService.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchDefinitionTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/discovery/PatchScannerTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/discovery/PatchTargetResolverTest.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/generation/PatchGenerationServiceTest.java`

**Interfaces:**
- Produces `Utf8Ordering.UNSIGNED_BYTES`, a `Comparator<String>` that compares UTF-8 byte sequences as unsigned values without normalization.
- `PatchDefinition.ORDERING` is priority, patch ID, source-pack load order, then source-pack ID using that comparator.
- Resolver winner ordering is descending load order, then descending source-pack ID with the same comparator.

- [ ] **Step 1: Write failing ordering tests**

Add a definition ordering assertion where two definitions share priority, ID, and load order but have source packs `"z-pack"` and `"a-pack"`; the lower unsigned UTF-8 source-pack ID must apply first. Add a resolver test whose equal-load-order winner is the higher unsigned UTF-8 source-pack ID. Add a scanner/generation test that records deterministic source ID order in output metadata.

- [ ] **Step 2: Run the focused ordering tests and verify they fail**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchDefinitionTest,PatchScannerTest,PatchTargetResolverTest,PatchGenerationServiceTest test`

Expected: failure because definition ordering omits source-pack ID and existing ordering uses Java string collation.

- [ ] **Step 3: Implement unsigned UTF-8 ordering at every contract boundary**

Encode both strings as UTF-8 and compare each byte with `Byte.toUnsignedInt`; compare lengths only after all shared bytes are equal. Use this comparator in definition application, scan source ordering, target source resolution, and source-pack IDs returned in generation plans. For the resolver, reverse the composed comparator as one unit so both load order and source ID select the highest provider.

- [ ] **Step 4: Run the focused ordering tests and verify they pass**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchDefinitionTest,PatchScannerTest,PatchTargetResolverTest,PatchGenerationServiceTest test`

Expected: PASS, including equal-priority/equal-ID definitions from different packs and equal-load-order resolver winners.

- [ ] **Step 5: Commit the ordering slice**

```bash
git add runtime/src/main/java/com/alechilles/patchwork/format/Utf8Ordering.java \
  runtime/src/main/java/com/alechilles/patchwork/engine/PatchDefinition.java \
  runtime/src/main/java/com/alechilles/patchwork/discovery/PatchScanner.java \
  runtime/src/main/java/com/alechilles/patchwork/discovery/PatchTargetResolver.java \
  runtime/src/main/java/com/alechilles/patchwork/generation/PatchGenerationService.java \
  runtime/src/test/java/com/alechilles/patchwork/engine/PatchDefinitionTest.java \
  runtime/src/test/java/com/alechilles/patchwork/discovery/PatchScannerTest.java \
  runtime/src/test/java/com/alechilles/patchwork/discovery/PatchTargetResolverTest.java \
  runtime/src/test/java/com/alechilles/patchwork/generation/PatchGenerationServiceTest.java
git commit -m "Feat: make Patchwork ordering total"
```

### Task 6: Publish the Patchwork authoring contract and conformance corpus

**Files:**
- Create: `docs/authoring-kit/v2/patch-definition.schema.json`
- Create: `docs/authoring-kit/v2/capabilities.json`
- Create: `runtime/src/test/resources/authoring-kit/v2/valid/replace-matching.json`
- Create: `runtime/src/test/resources/authoring-kit/v2/valid/move-matching.json`
- Create: `runtime/src/test/resources/authoring-kit/v2/invalid/duplicate-key.json`
- Create: `runtime/src/test/resources/authoring-kit/v2/invalid/missing-require-format.json`
- Create: `runtime/src/test/resources/authoring-kit/v2/invalid/ambiguous-match.json`
- Modify: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchDefinitionTest.java`
- Modify: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchEngineTest.java`
- Modify: `docs/Patch-Format.md`
- Modify: `docs/Operations.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- The schema names `FormatVersion: 2`, closes root/operation/condition descriptor shapes, and leaves `Value`, matcher data, expected JSON values, and macro options as explicit JSON data containers.
- `capabilities.json` declares supported versions `[1, 2]`, operations, `TargetProvidedBy`, and no built-in macro descriptor version.
- Runtime tests load the shipped valid and invalid fixtures, so the docs kit and behavior cannot drift.

- [ ] **Step 1: Write failing fixture-consumption tests**

Add a parameterized or loop-based test that reads every JSON file below `authoring-kit/v2/valid`, parses it using `PatchDefinitionReader`, and applies its stated source input. Add invalid-fixture assertions that parse/discovery rejects each file before an engine application begins.

- [ ] **Step 2: Run the fixture tests and verify they fail**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchDefinitionTest,PatchEngineTest test`

Expected: failure because the authoring-kit corpus and fixture loader do not exist.

- [ ] **Step 3: Add schema, capabilities, and minimum conformance fixtures**

Create a draft-2020-12 schema that has closed definition/operation/condition objects and separate `oneOf` operation shapes. Include fixtures for valid `ReplaceMatching`, `RemoveMatching`, `MoveMatching`, strict `$Equals`/`$Contains`, and `TargetProvidedBy`; include invalid duplicate key, missing/misplaced/mismatched sentinel, unknown operation field, empty matcher, ambiguous exactly-one, invalid pointer escape, and invalid move anchor cases. Each fixture contains only portable JSON and is named after its asserted behavior.

- [ ] **Step 4: Update user-facing documentation**

Document `FormatVersion`, `RequireFormat`, strict pointer/matcher semantics, the three matcher-based operations, match policies, exact provider condition, and total ordering in `docs/Patch-Format.md`. Add the authoring-kit location and current capability document to `docs/Operations.md`. Add one unreleased changelog entry describing format-2 support and explicit legacy compatibility.

- [ ] **Step 5: Run the full runtime and packaging verification**

Run: `./mvnw.cmd test`

Expected: PASS with all runtime, standalone, and integration tests green.

Run: `./mvnw.cmd -pl standalone -am verify`

Expected: PASS and the standalone artifact still packages against the Hytale system dependency path.

- [ ] **Step 6: Inspect the final change and commit**

Run: `git diff --check`

Run: `git status --short`

Then commit the documentation, schema, fixtures, and remaining test changes:

```bash
git add docs/authoring-kit/v2 runtime/src/test/resources/authoring-kit/v2 \
  docs/Patch-Format.md docs/Operations.md CHANGELOG.md \
  runtime/src/test/java/com/alechilles/patchwork/engine/PatchDefinitionTest.java \
  runtime/src/test/java/com/alechilles/patchwork/engine/PatchEngineTest.java
git commit -m "Docs: publish Patchwork format 2 authoring kit"
```

## Self-Review

**Spec coverage:** Task 1 covers root versioning, closed structure, duplicate keys, and the `RequireFormat` compatibility guard. Task 2 covers strict matching and RFC 6901 semantics while keeping format 1. Task 3 covers all matcher-based array operations and requiredness. Task 4 covers `TargetProvidedBy` and the single resolver snapshot. Task 5 covers total unsigned UTF-8 ordering. Task 6 publishes the schema, capability document, fixtures, and user-facing documentation.

**Intentional scope boundary:** HyCreator’s Rust authoring module, UI draft state, rebase display, runtime-status UI, macro descriptor editor metadata, and environment snapshots remain HyCreator-owned. Patchwork only publishes the portable data/runtime contract they consume.

**Placeholder scan:** The plan contains no deferred implementation items; each task names exact files, interfaces, failing tests, passing tests, and a commit.

**Type consistency:** `PatchDefinitionReader` supplies byte-level duplicate-key validation to `PatchScanner`; `PatchDefinition` supplies the selected format version to `PatchOperation`; the engine consumes operation format semantics; `PatchGenerationService` supplies the resolved target record to condition evaluation; `Utf8Ordering` is reused at all ordering boundaries.
