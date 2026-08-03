# Core Patchwork Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship every approved Patchwork feature—format-free authoring, matching and cross-asset operations, target globs, conflict policies, automatic regeneration, and confirmed live reload—without expanding ordinary failures into exhaustive theoretical hardening work.

**Architecture:** Keep the current pure engine and immutable generation snapshot. Add author-facing features first, then layer target-local conflict policy and a single elected reload controller on top. Native Asset Editor fields, portable JSON artifacts, fixtures, and docs change in the same feature slice; live reload is reported only after the supported Hytale event path confirms it.

**Tech Stack:** Java 25, Gson, Hytale Asset Editor codecs, JUnit 5, Maven Wrapper.

## Scope and constraints

- Already complete: neutral unversioned parsing/editor support and immutable generation input snapshots.
- Preserve explicit format 1/2 behavior and lossless reopening of explicit files.
- New neutral files never require `FormatVersion` or `RequireFormat`.
- Exclude `Alechilles:Patchwork_GeneratedPatches` from discovery and source lookups.
- Prefer normal validation, deterministic ordering, and focused behavior tests. Do not add adversarial race handling beyond the repository's existing safe file-resolution primitives.
- Run focused tests for each task and `./mvnw.cmd test` after each complete user-facing milestone.
- Do not add artificial race hooks, descriptor-level retry loops, or exhaustive failure simulation beyond the existing safe resolver and normal event API behavior.
- Automatic regeneration handles directory-pack source edits, exact sources, definition files, glob prefixes, and pack registration/removal. Archive-pack edits, Common assets, custom/unknown stores, disabled monitoring, and unconfirmed reloads remain manual or restart-required.
- A generated file on disk is not called hot-reloaded. Report `HOT_RELOADED` only after the supported Hytale monitor/load event path confirms the generated provider and expected asset path.

---

### Task 1: Mutation tracing and matching array operations

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/engine/MutationEffect.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/engine/JsonValueFingerprint.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchEngine.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchOperation.java`
- Modify: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchOperationAsset.java`
- Modify: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchEngineTest.java`
- Modify: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchNativeAuthoringSchemaTest.java`
- Create: neutral valid/invalid matching fixtures and update neutral schema/capabilities, `docs/Operations.md`, and `CHANGELOG.md`.

**Interfaces:**

```java
public record MutationEffect(String target, String patchId, String sourcePackId,
        String operationId, long operationOrder, String path, Kind kind,
        String valueFingerprint) {
    public enum Kind { WRITE, ARRAY_MEMBERSHIP, ARRAY_ORDER }
}

public record PatchEngine.DefinitionResult(JsonObject patched, List<String> applied,
        List<String> skipped, List<MutationEffect> effects, long nextOperationOrder) { }
```

- [ ] **Step 1: Write focused failing tests**

Add one trace test for a merge that writes an equal leaf and a changed leaf, plus tests that `MergeMatching` deep-merges matching objects and `UpsertMatching` inserts one object when no match exists.

```java
assertEquals(List.of("/A", "/Nested/B"), result.effects().stream()
        .map(MutationEffect::path).toList());
assertEquals(1, upserted.getAsJsonArray("Rows").size());
```

- [ ] **Step 2: Verify RED**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchEngineTest,PatchNativeAuthoringSchemaTest test
```

Expected: the new operations/effects are unavailable.

- [ ] **Step 3: Implement the minimum engine contract**

Add canonical SHA-256 fingerprints without retaining raw values. Add `ApplicationContext`/`DefinitionResult`; use `PatchLanguage.closedStructure()` for neutral strict pointer and matcher behavior. Implement `MergeMatching` and `UpsertMatching` from a pre-operation array snapshot. `UpsertMatching` inserts exactly one object only when no match exists; Before/After require `Find`.

- [ ] **Step 4: Complete authoring parity**

Expose both operations with typed `Path`, `Match`, `Value`, `MatchPolicy`, `Position`, and `Find` fields. Add only their supported fields to neutral schema/capabilities and fixtures; document concise merge/upsert examples.

- [ ] **Step 5: Verify and commit**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchEngineTest,PatchDefinitionTest,PatchNativeAuthoringSchemaTest,PatchDefinitionAssetTest test
git add runtime/src/main runtime/src/test docs/authoring-kit/neutral docs/Operations.md CHANGELOG.md
git commit -m "Feat: add matching patch operations"
```

### Task 2: Cross-asset merge operations

**Files:**
- Modify: `PatchOperation.java`, `PatchEngine.java`, `PatchOperationAsset.java`, and their focused tests.
- Create: neutral cross-asset fixtures; modify neutral schema/capabilities, `docs/Operations.md`, and `CHANGELOG.md`.

- [ ] **Step 1: Write focused failing tests**

```java
assertEquals(json("{\"A\":9,\"B\":2}"), result.getAsJsonObject("Shared"));
```

Cover an exact `OverlayFromAsset`, `MergeObjectFromAsset` with `SourcePath`, missing source under `Required:false`, and self-overlay rejection.

- [ ] **Step 2: Implement exact snapshot reads**

`OverlayFromAsset` deep-merges an exact source asset onto the working root; `MergeObjectFromAsset` deep-merges a selected source object into an existing selected target object. Resolve only through `ApplicationContext.assets()`, reject source globs/self-overlay, and treat missing source/path/type as ordinary applicability failures.

- [ ] **Step 3: Complete editor/schema/docs and verify**

Use labels **Overlay entire asset** and **Merge object from asset**. Document source-wins merge direction and `SourcePath` default.

```bash
./mvnw.cmd -pl runtime -Dtest=PatchEngineTest,PatchGenerationServiceTest,PatchNativeAuthoringSchemaTest,PatchDefinitionAssetTest test
./mvnw.cmd test
git add runtime/src/main runtime/src/test docs/authoring-kit/neutral docs/Operations.md CHANGELOG.md
git commit -m "Feat: add cross-asset merge operations"
```

### Task 3: Explicit target globs and dependency metadata

**Files:**
- Create: `PatchTargetSelector.java`, `PatchTargetExpander.java`, `GenerationDependencyIndex.java`.
- Modify: `PatchDefinition.java`, `PatchScanner.java`, `PatchGenerationService.java`, `PatchDefinitionAsset.java`, focused tests, neutral artifacts, `docs/Patch-Format.md`, `README.md`, and `CHANGELOG.md`.

- [ ] **Step 1: Write focused selector tests**

```java
assertEquals(List.of("Server/NPC/Bear.json", "Server/NPC/Wolf.json"),
        expander.expand(selectors, snapshot));
assertFalse(PatchTargetSelector.parse("glob:Server/Item/*.json")
        .matches("Server/Item/Sub/A.json"));
```

- [ ] **Step 2: Implement deterministic expansion**

Only a `glob:` prefix enables `*`, `**`, and `?`; exact paths reject raw wildcard characters. Expand against snapshot paths, exclude generated output, deduplicate, and order with unsigned UTF-8. Bind each result to an ordinary concrete definition.

- [ ] **Step 3: Publish only useful dependencies**

Record definition identity, expanded targets, exact cross-asset sources, and glob stable prefixes in `GenerationDependencyIndex`; this is metadata for future reload work, not an automatic watcher.

- [ ] **Step 4: Complete authoring parity, verify, and commit**

Add Target/Targets help and neutral fixtures/schema constraints.

```bash
./mvnw.cmd -pl runtime -Dtest=PatchTargetExpanderTest,PatchScannerTest,PatchGenerationServiceTest,GenerationPlanFactoryTest,PatchNativeAuthoringSchemaTest test
git add runtime/src/main runtime/src/test docs/authoring-kit/neutral docs/Patch-Format.md README.md CHANGELOG.md
git commit -m "Feat: expand explicit patch target globs"
```

### Task 4: Conflict diagnostics and target-local policy

**Files:**
- Create: `conflict/ConflictRecord.java`, `ConflictReport.java`, `ConflictAnalyzer.java`, and `ConflictPolicy.java`.
- Modify: `PatchDefinition.java`, `PatchGenerationService.java`, `PatchStatusSnapshot.java`, administration/command classes, focused tests, neutral authoring artifacts, `docs/Operations.md`, `README.md`, and `CHANGELOG.md`.

- [ ] **Step 1: Write focused failing overlap tests**

```java
assertEquals(MATERIAL_OVERLAP, report.records().getFirst().classification());
assertFalse(report.records().getFirst().toString().contains("valueFingerprint"));
```

Cover same/cross-pack overlaps, redundant equal writes, no conflict within one definition, and `Reject` retaining the prior bytes only for its affected target.

- [ ] **Step 2: Add analysis and policy**

Compare accepted concrete effects by `(target, path, kind)` and retain deterministic redacted rows. `Report` remains the default; `Allow` applies while suppressing rows introduced by that definition; `Reject` stops only that target, retains its previously published generated bytes when present, and leaves unrelated targets updating normally. Store the latest accepted report in administration state.

- [ ] **Step 3: Add one optional command surface**

Implement `/patchwork conflicts` with an optional exact target argument. Render a bounded, value-redacted list and summary counts.

- [ ] **Step 4: Complete editor/schema/docs, verify, and commit**

```bash
./mvnw.cmd -pl runtime -Dtest=ConflictAnalyzerTest,PatchGenerationServiceTest,PatchworkAdministrationServiceTest,PatchworkCommandOwnershipTest test
./mvnw.cmd test
git add runtime/src/main runtime/src/test docs/Operations.md README.md CHANGELOG.md
git commit -m "Feat: add patch conflict policies"
```

### Task 5: Automatic regeneration and confirmed live reload

**Files:**
- Create: `reload/AutomaticReloadController.java`, `PatchworkSourceEvent.java`, `HytaleAssetEventBridge.java`, and `HytaleReloadEvidenceCorrelator.java`.
- Modify: `PatchworkRuntimeHost.java`, `HytaleEarlyLoadComposition.java`, `PatchworkAdministrationService.java`, `PatchReloadCoordinator.java`, `PatchReloadTracker.java`, status snapshots, lifecycle tests, `docs/Operations.md`, `docs/Runtime-Election.md`, `README.md`, and `CHANGELOG.md`.

- [ ] **Step 1: Write focused controller and confirmation tests**

```java
controller.onSourceEvent(epoch, modified("Pack:Example", "Server/Item/Items/A.json"));
scheduler.advance(Duration.ofSeconds(1));
assertEquals(1, reloads.started());

assertFalse(correlator.confirm(wrongProviderEvent));
assertTrue(correlator.confirm(generatedProviderEvent));
```

Cover one-second debounce, one dirty follow-up, stale epoch rejection, generated-pack event exclusion, and provider/path confirmation. Do not model synthetic filesystem races.

- [ ] **Step 2: Implement one elected automatic path**

Only the elected runtime creates the controller. It listens to normal Hytale store/pack/monitor events, ignores generated output, matches the latest dependency metadata, and invokes the existing reload coordinator through an ownership-epoch-checked internal entry point. Keep `/patchwork reload` as the manual fallback.

- [ ] **Step 3: Implement conservative confirmation**

For known server stores, write generated bytes through the existing transactional path, then require matching generated-provider and asset-path evidence before reporting `HOT_RELOADED`. For Common, custom/unknown stores, a disabled monitor, or no matching confirmation, report generated-on-disk/restart-required; do not claim success.

- [ ] **Step 4: Validate Hytale references, verify, and commit**

```bash
./mvnw.cmd -pl runtime -Dtest=AutomaticReloadControllerTest,HytaleAssetEventBridgeTest,HytaleReloadEvidenceCorrelatorTest,PatchReloadCoordinatorTest,PatchworkRuntimeHostTest test
./mvnw.cmd test
./mvnw.cmd -pl standalone -am verify
git add runtime/src/main runtime/src/test standalone/src/test docs/Operations.md docs/Runtime-Election.md README.md CHANGELOG.md
git commit -m "Feat: automate confirmed Patchwork reloads"
```

Validate changed Hytale-facing source files against the Hytale Workshop API before the Maven verification.
