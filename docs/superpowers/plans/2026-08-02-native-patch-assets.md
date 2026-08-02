# Native Patch Asset Registration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register portable Patchwork definitions as a native Hytale asset type without changing runtime generation semantics or applying definitions twice.

**Architecture:** Add a runtime-owned asset facade whose Hytale codec exposes the stable patch root and operation fields. Production JSON decoding validates the original duplicate-aware stream and retains the portable JSON as the lossless save representation; a BSON-compatible mirror supports Hytale's editor APIs without rounding high-precision source values. The standalone plugin registers one `HytaleAssetStore` for `Patchwork/Patches`; Patchwork's existing pack scanner remains the only input to generation, so native loading supplies editor/schema validation but not a second application path.

**Tech Stack:** Java 25, Hytale `AssetBuilderCodec`/`HytaleAssetStore`, Gson, BSON, Maven, JUnit Jupiter.

## Global Constraints

- Keep the reusable runtime free of Hytale plugin identity files.
- Store portable files below `Server/Patchwork/Patches` with the `.json` extension.
- Preserve Patchwork format-1 compatibility and validate format-2 documents through the existing parser.
- Do not add a custom live-application or restart-reporting flow.
- Keep `PatchScanner` as the sole definition source used by generation.
- Use Git Bash and run `./mvnw.cmd test`; run `./mvnw.cmd -pl standalone -am verify` because standalone packaging changes.

---

### Task 1: Add the native patch asset codec and store

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchJsonValueCodec.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchOperationAsset.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchDefinitionAsset.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/authoring/PatchDefinitionAssetStore.java`
- Test: `runtime/src/test/java/com/alechilles/patchwork/authoring/PatchDefinitionAssetTest.java`

**Interfaces:**
- Produces `PatchDefinitionAsset.CODEC`, an `AssetCodec<String, PatchDefinitionAsset>` facade backed by an `AssetBuilderCodec` schema.
- Produces `PatchDefinitionAssetStore.create()`, returning a store whose path is `Patchwork/Patches` and extension is `.json`.
- Produces `PatchDefinitionAsset.toPortableJson()`, which feeds the existing `PatchDefinition.parseAll` validator.

- [x] **Step 1: Write failing codec and schema tests**

Add tests that decode a representative format-2 definition, retain a primitive operation `Value`, reject a missing `RequireFormat` sentinel, and assert that the generated schemas expose root fields such as `Target`/`Operations` and operation fields such as `Op`/`Match`/`Value`.

- [x] **Step 2: Run the focused test and verify it fails because the native asset classes do not exist**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchDefinitionAssetTest test`

Expected: test compilation fails on missing `PatchDefinitionAsset` and `PatchDefinitionAssetStore`.

- [x] **Step 3: Implement the minimal codec model**

Use `AssetBuilderCodec.builder(...)` for the definition, `BuilderCodec.builder(...)` for operations, `ArrayCodec` for arrays, and a small `Codec<BsonValue>` for deliberately arbitrary JSON containers. Attach a root validator equivalent to:

```java
try {
    PatchDefinition.parseAll(asset.toPortableJson(), "native-asset-store", asset.getId(), 0);
} catch (IllegalArgumentException failure) {
    results.fail(failure.getMessage());
}
```

- [x] **Step 4: Implement the store factory**

Build the store with:

```java
HytaleAssetStore.builder(PatchDefinitionAsset.class, new DefaultAssetMap<>())
    .setPath("Patchwork/Patches")
    .setCodec(PatchDefinitionAsset.CODEC)
    .setKeyFunction(PatchDefinitionAsset::getId)
    .build();
```

- [x] **Step 5: Run the focused test and verify it passes**

Run: `./mvnw.cmd -pl runtime -Dtest=PatchDefinitionAssetTest test`

Expected: PASS.

### Task 2: Register the store from the standalone plugin

**Files:**
- Modify: `standalone/src/main/java/com/alechilles/patchwork/standalone/PatchworkPlugin.java`

**Interfaces:**
- Consumes `PatchDefinitionAssetStore.create()`.
- Produces a normal Hytale asset-store registration during plugin `setup()`.

- [x] **Step 1: Register before bootstrapping the standalone service**

Add:

```java
getAssetRegistry().register(PatchDefinitionAssetStore.create());
```

before the standalone runtime bootstrap. Hytale's `RegisterAssetStoreEvent` then supplies the type and generated codec schema to the native Asset Editor.

- [x] **Step 2: Compile and run standalone lifecycle tests**

Run: `./mvnw.cmd -pl standalone -am -Dtest=PatchDefinitionAssetTest,PatchworkPluginLifecycleTest test`

Expected: PASS.

### Task 3: Document the registration boundary and verify packaging

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`

- [x] **Step 1: Document first-class asset editing and single-source generation**

Explain that native registration exposes `Server/Patchwork/Patches/*.json` to Hytale's Asset Editor and validates through the portable Patchwork contract. State that registration does not create a second generation input or custom reload flow; `PatchScanner` remains authoritative.

- [x] **Step 2: Run full source verification**

Run: `./mvnw.cmd test`

Expected: reactor `BUILD SUCCESS` with zero failures.

- [x] **Step 3: Run packaging verification**

Run: `./mvnw.cmd -pl standalone -am verify`

Expected: reactor `BUILD SUCCESS`, with the standalone shaded artifact retaining the runtime classes and the reusable runtime remaining manifest-free.

- [x] **Step 4: Validate Hytale API references**

Run Hytale Workshop's `validate_hytale_code_refs` against the new engine-touching Java files and resolve any not-found references before committing.

- [x] **Step 5: Commit only this implementation**

Stage the new plan, authoring classes/tests, standalone registration, README, and changelog. Do not stage the pre-existing dirty authoring-schema follow-up.
