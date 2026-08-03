# Advanced Patchwork Pipeline Design

**Date:** 2026-08-03
**Status:** Approved design, pending implementation planning

## Summary

Patchwork will extend its patch language and generation pipeline with matching merges and upserts, explicit wildcard target selection, cross-asset merge operations, concrete conflict reporting, and safely confirmed standalone live reload.

The work is delivered as vertical slices through one staged pipeline:

```text
discover
  -> expand targets
  -> resolve immutable source snapshot
  -> apply purely in memory and collect mutation traces
  -> analyze conflicts and enforce policy
  -> publish atomically
  -> confirm reload where Hytale provides sufficient evidence
```

Every public authoring capability must be usable through Hytale's native Asset Editor in the same slice that introduces it. Authors should choose intent and enter relevant values; they should not have to select a Patchwork format version or understand runtime capability negotiation.

This document specifies future behavior. It does not claim that any capability described below is already implemented.

## Goals

- Merge fields into matched array objects without replacing each whole entry.
- Update matched entries or insert one entry when no match exists.
- Apply one definition to a deterministic set of target assets selected by explicit glob syntax.
- Merge data from another exact-path asset without creating order-dependent patch chains.
- Identify actual overlapping writes and initially report them without changing output.
- Later allow the definition that introduces an overlap to choose report, allow, or reject behavior.
- Regenerate automatically in the standalone runtime and report hot reload only after correlated Hytale evidence confirms it.
- Keep failures isolated to affected targets and preserve last-known-good generated content during invalid edits.
- Make all new syntax guided, documented, and lossless in the native Asset Editor and portable authoring kit.

## Non-goals

- Raw regular expressions for target selection.
- Importing the patched output of another target or creating dependency-order semantics between targets.
- A root-level `Imports` phase separate from ordered operations.
- Self-overlay through `OverlayFromAsset`.
- Locks, ownership, permissions, or authorization rules for conflicts.
- Live reload claims based only on a successful disk write or an uncorrelated asset event.
- Initial live reload for Common assets, immutable archive packs, or ModData inputs.
- A new stable embedding API unless implementation proves one is necessary.
- Editor-only fields, wrappers, or discriminators in portable definitions.

## Compatibility and Language Evolution

### Neutral definitions

New definitions under `Server/Patchwork/Patches` use one modern, additive, unversioned language. Authors do not enter `FormatVersion`, `RequireFormat`, or an equivalent compatibility sentinel.

Capability negotiation belongs to tools and installation metadata:

- Consuming mods declare the minimum Patchwork runtime version they require.
- The authoring kit publishes machine-readable capabilities for tools.
- Hytale's native Asset Editor exposes supported operations and fields from the installed runtime's schema.
- A runtime that encounters an unknown modern operation or structural field rejects the whole definition with a useful diagnostic.

Unknown syntax is never treated as ordinary operation inapplicability. `Required: false` controls understood applicability failures such as a missing path, no match, or incompatible selected type. It does not authorize an older runtime to ignore syntax it cannot interpret.

A mod that requires a newer Patchwork runtime but is installed with an older one is an installation error. Patchwork still fails closed so that error cannot silently produce a partially patched asset.

### Existing versioned definitions

Existing definitions that explicitly declare format 1 or format 2 retain their documented behavior. Missing format under the legacy `Server/Tamework/Patches` root continues to mean format 1. This design does not reinterpret, migrate, or silently normalize those files.

Once the neutral language ships, it evolves additively. Existing operation names and structural fields keep their established meanings.

## Generation Architecture

### Immutable generation snapshot

Each generation epoch captures an immutable view of:

- the winning original source asset for every eligible path;
- pack identity and deterministic pack order;
- discovered definitions and their source files;
- the asset inventory used for wildcard expansion; and
- external applicability inputs already supported by Patchwork.

Patchwork's generated pack is excluded from source resolution, source-asset operations, target expansion, and dependency discovery. Source operations always read the original winning asset from this snapshot, never another target's patched result.

The same snapshot feeds every pipeline phase for the epoch. A later file or pack event marks the controller dirty and schedules another epoch; it does not mutate the epoch already in progress.

### Pure application result

The reusable engine applies an ordered definition set to in-memory JSON without filesystem or Hytale lifecycle side effects. For each target it returns:

- candidate output JSON;
- applied and skipped operation outcomes;
- target-local diagnostics; and
- a concrete mutation trace.

Generation, conflict analysis, publication, and reload orchestration consume this result. The reusable runtime remains free of standalone Hytale plugin identity and lifecycle policy.

### Determinism and isolation

- Expanded paths, definitions, and reported conflicts use their specified deterministic comparators.
- A structural or definition-validation failure rejects every target expanded from that definition. Once a definition is validly parsed, one target's applicability, result validation, import, or conflict-policy failure does not prevent unrelated valid targets from updating.
- Invalid edited definitions preserve the last-known-good generated bytes for targets previously produced from those definitions.
- Patchwork never mutates source assets.
- Publication is atomic per generated asset.

## Patch Operations

All new operations participate in normal ordered operation evaluation and support the existing optional `Id` and `Required` fields. Applicability failures use existing required/optional behavior unless this design explicitly requires structural rejection.

### `MergeMatching`

`MergeMatching` selects object entries in an array and deep-merges `Value` into each selected entry.

Fields:

- `Path`: pointer to the target array.
- `Match`: recursive Patchwork matcher used to select entries.
- `Value`: object to deep-merge into each selected object.
- `MatchPolicy`: optional existing policy; defaults to `ExactlyOne`.

Every selected value must be an object. Object fields are merged recursively; source `Value` leaves win over existing leaves. Unrelated fields remain. Arrays and scalar leaves are values, not recursively merged collections.

Selection is evaluated against the array snapshot immediately before the operation. Applying the merge does not change which entries that same operation selected.

### `UpsertMatching`

`UpsertMatching` updates selected object entries by deep-merging `Value`; if there are no matches, it inserts one copy of `Value` into the array.

Fields:

- `Path`: pointer to the target array.
- `Match`: recursive Patchwork matcher.
- `Value`: object used for both merge and insertion.
- `MatchPolicy`: optional existing policy; defaults to `ExactlyOne`.
- `Position`: optional insertion position; defaults to `End`.
- `Find`: required only by a relative `Before` or `After` insertion position.

When at least one entry matches, normal `MatchPolicy` selection applies and each selected value must be an object. When zero entries match, exactly one value is inserted, including when `MatchPolicy` is `All`.

Both item selection and any insertion anchor are evaluated from the pre-operation array snapshot. As with the existing `Insert.Find` contract, `Find` selects the first matching anchor; no match is an applicability failure. Self-anchoring cannot arise in the insertion branch because insertion occurs only when `Match` selected no existing item.

### `OverlayFromAsset`

`OverlayFromAsset` deep-merges an entire exact-path source asset onto the working target root.

Fields:

- `Source`: exact asset path.

Both source and working target roots must be objects. Source leaves win, unrelated target fields remain, and later operations may override imported leaves. The source is resolved from the immutable generation snapshot. `Source` is intentionally exact-path only; it does not accept a glob.

Self-overlay, where `Source` resolves to the current target path, is rejected. This prevents a misleading no-op and avoids confusion about whether the source is original or already patched.

The native Asset Editor labels this operation **Overlay entire asset**.

### `MergeObjectFromAsset`

`MergeObjectFromAsset` deep-merges one selected source object into one selected existing target object.

Fields:

- `Source`: exact asset path.
- `SourcePath`: optional pointer into the source asset; defaults to the source root.
- `Path`: pointer to an existing object in the working target.

The selected source value and target destination must both be objects. Source leaves win and unrelated destination fields remain. The source asset and `SourcePath` are resolved against the immutable generation snapshot; `Path` is resolved against the current working target at this operation's position.

The native Asset Editor labels this operation **Merge object from asset**.

### Cross-asset failure behavior

For both source operations, a missing source asset, missing selected path, or incompatible selected type is an applicability failure. `Required` determines whether that understood failure rejects the target or skips the operation. An invalid operation structure remains a definition error.

Both operations record every affected concrete target leaf in the mutation trace.

## Wildcard Target Expansion

`Target` and entries in `Targets` accept either an exact asset path or an explicit selector beginning with `glob:`.

Supported glob tokens are:

- `*` for zero or more characters within one path segment;
- `**` for zero or more path segments; and
- `?` for one character within one path segment.

Raw regular expressions and implicit wildcard interpretation are not supported. The `glob:` prefix makes author intent and validation unambiguous.

Expansion runs against the immutable union inventory of eligible original assets, excluding Patchwork's generated pack. Exact and expanded results are normalized, deduplicated, and sorted by unsigned UTF-8 path bytes before application. A selector that matches nothing emits a warning and produces no targets; it does not synthesize an asset.

The generation dependency index records each selector and the inventory scope that can change its expansion. Pack registration, pack removal, or a relevant asset create/delete therefore schedules reevaluation even if the last expansion was empty.

## Mutation Traces and Conflict Analysis

### Trace model

The engine records every successfully applied write, including a write whose resulting value equals the value already present. It does not trace skipped or inapplicable operations merely because they mention a path. A trace entry includes:

- target asset path;
- concrete target leaf path or synthetic collection effect;
- source pack and patch definition identity;
- operation ID or ordered operation position;
- deterministic application order; and
- effect classification.

Values are not retained in the public conflict record. The analyzer may compare in-memory values during the epoch to classify redundant identical writes, but diagnostics do not disclose those values.

Object changes trace their affected leaf paths. Array structural operations additionally trace synthetic membership or order effects so inserts, removals, moves, and overlapping element writes are visible even when JSON Pointer indexes shift.

### Conflict definition

A conflict is an overlap between actual effects from different definitions on the same target. Multiple operations within one definition are presumed intentional and are not reported as conflicts.

The analyzer distinguishes:

- same-pack and cross-pack overlaps;
- redundant identical writes; and
- material overlapping effects.

Conflict analysis runs after pure in-memory application and before publication or reload.

### Initial report-only release

The first conflict slice is observational. It does not change generated output.

Patchwork reports summary counts in normal status output and provides:

```text
/patchwork conflicts [target]
```

The detailed report identifies the target, affected path/effect, earlier and later definitions and operations, order, same/cross-pack classification, and whether the result was an identical redundant write. It does not print the written values.

### Configurable enforcement

A later slice adds optional root-level `ConflictPolicy` to a definition:

- `Report` is the default and preserves output while reporting overlaps.
- `Allow` accepts overlaps introduced by this definition without reporting them as actionable conflicts.
- `Reject` rejects this definition's affected target when it introduces an overlap.

Policy belongs to the later definition that introduces the overlap in deterministic application order. It is not a lock, permission, ownership claim, or authorization over earlier or later mods. `Reject` fails the target's candidate generation for that epoch: later definitions are not applied to that target, the candidate is not published, and the previous generated bytes are retained when available. Other targets continue normally.

## Automatic Generation and Confirmed Live Reload

### Ownership and election

Only the elected standalone Patchwork runtime owns the `AutomaticReloadController`. Election fencing applies to event handling, generation, publication, and success reporting so an obsolete runtime cannot write or confirm a newer epoch.

Embedded generation continues to use the shared pure engine but does not acquire standalone lifecycle responsibilities unless separately designed.

### Trigger sources

The controller reacts to:

- mutable Patchwork definition creation, modification, and deletion, including edits that make a previously valid definition invalid;
- changes to exact targets and cross-asset sources in the last successful dependency plan;
- asset creation or deletion that can change a wildcard expansion;
- relevant pack registration or removal; and
- relevant source-provider changes.

The last successful plan indexes definition files, expanded targets, exact cross-asset sources, and wildcard inventory roots. Generated-pack events are never generation triggers; they are consumed only as possible confirmation evidence.

Archive/hash packs are immutable and do not produce automatic source-change regeneration. ModData documents are not watched in the initial release; authors use manual `/patchwork reload` after those changes.

### Debounce and epochs

Source events are debounced over a quiet period. If another relevant event arrives while generation or publication is running, the controller sets one dirty flag and starts one follow-up epoch after the current epoch completes. It does not run concurrent generation epochs.

Hytale's asset watcher already performs file-growth stabilization and quiet batching. Patchwork still maintains its own generation debounce because one logical change may arrive through multiple definition, inventory, and pack events.

### Generated pack preparation

Before registering Patchwork's generated directory pack, the standalone runtime creates `Common/` and `Server/<store path>` directories for every currently registered safe server asset-store path. This lets Hytale install monitors even when a generated store directory begins empty.

Later dynamically registered asset stores are handled through the corresponding store-registration lifecycle. Store paths are normalized and accepted only when they remain beneath the generated pack's `Server` directory.

### Publication

Each generated asset is written to a sibling temporary file with a non-asset extension, then atomically moved into place when the filesystem supports it. This avoids Hytale decoding partially written JSON or interpreting the temporary file as another asset.

An invalid edited definition does not erase its previous generated targets. Patchwork retains last-known-good generated bytes for those targets, reports the source failure, and continues updating unrelated valid targets.

### Confirmation contract

A successful disk write means **generated on disk**, not **hot-reloaded**.

Patchwork reports **hot-reloaded** only after correlating Hytale asset lifecycle evidence with all of the following:

- the pending generation epoch;
- expected asset store, key, and normalized path;
- the generated pack as the winning provider after the event; and
- the expected generated disk hash.

For removals, confirmation requires observing the generated provider disappear and the expected lower provider or expected absence become visible.

Stale events, path/provider mismatches, watcher overflow, stopped monitors, load failures, or confirmation timeouts never produce a success claim. Patchwork reports the result as stale or restart-required and retains enough expected-state evidence for diagnostics and rollback reasoning.

If Hytale's asset watching is disabled, automatic generation is unavailable. A manual reload may still update generated files on disk, but Patchwork reports that a restart is required.

Common assets remain restart-required initially because Hytale does not expose equivalent server-side success evidence for safe confirmation.

## Native Asset Editor and Authoring Contract

Native Hytale Asset Editor usability is part of feature completeness, not follow-up polish. Every public language change in this design ships with its native codec/schema, portable schema and capabilities, conformance fixtures, documentation, and schema-focused tests.

### Guided operation authoring

- `MergeMatching`, `UpsertMatching`, `OverlayFromAsset`, and `MergeObjectFromAsset` appear in the operation selector with beginner-facing descriptions.
- Operation alternatives expose only their relevant fields when Hytale's renderer supports discriminated alternatives.
- `Match` and `Find` use the typed recursive matcher schema.
- `MatchPolicy` and `Position` use documented finite choices.
- `Value` uses the guided recursive object schema where these operations require an object.
- Source operations use the labels **Overlay entire asset** and **Merge object from asset**, and document exact-path source resolution, merge direction, defaults, and applicability failures.
- `ConflictPolicy` is a documented finite choice with the default and consequence of each value.
- Target fields explain exact paths versus the `glob:` syntax with short examples and make the lack of raw-regex support obvious.

New neutral definitions do not show or suggest `FormatVersion` or `RequireFormat`. Existing accepted versioned and legacy definitions still reopen and save losslessly.

### Portable and lossless representation

The Asset Editor writes the same portable JSON consumed by Patchwork and external tools. It must not serialize editor-only discriminators, wrapper objects, hidden metadata, or normalized replacement values.

Opening and saving must preserve accepted legacy casing, JSON `null`, high-precision numbers, and opaque accepted extensions exactly as required by the established portable-source contract. The runtime parser remains the final authority for cross-field and applicability rules that Hytale's schema cannot express.

### Authoring verification

Schema tests verify:

- every new operation and policy is selectable and has nonblank documentation;
- recursive matcher and JSON schemas terminate through named references;
- each operation alternative exposes its relevant required and optional fields;
- source, target, merge direction, defaults, and failure behavior are described;
- exact and glob targets are understandable without external format knowledge; and
- representative new, versioned, and legacy documents reopen and save without semantic or precision loss.

## Delivery Slices

1. **Language baseline and mutation tracing**: establish neutral-definition parsing rules, capability metadata, pure application results, and concrete effect traces without changing conflict behavior.
2. **New operations**: implement `MergeMatching`, `UpsertMatching`, `OverlayFromAsset`, and `MergeObjectFromAsset` end to end, including native authoring.
3. **Target expansion and dependency index**: implement explicit glob expansion, deterministic inventory handling, and dependencies needed for later automatic regeneration.
4. **Conflict reporting**: classify traces and expose report-only status and `/patchwork conflicts [target]`.
5. **Conflict policies**: add `Report`, `Allow`, and target-local `Reject` enforcement.
6. **Automatic generation**: add elected, debounced dependency-driven standalone regeneration and atomic publication while reporting disk state accurately.
7. **Confirmed standalone live reload**: add generated-pack monitor preparation, event correlation, provider/hash confirmation, removal confirmation, and restart-required fallbacks.

Every slice that changes public behavior also updates:

- native Asset Editor codecs and schemas;
- portable JSON Schema and capability metadata;
- valid and invalid conformance fixtures;
- runtime and schema tests;
- user documentation and `CHANGELOG.md`; and
- standalone packaging verification when packaging changes.

## Testing and Verification

### Engine and language tests

- `MergeMatching` policy selection, deep merge, type failures, and snapshot matching.
- `UpsertMatching` update and zero-match insertion for every policy and position.
- Cross-asset merge direction, defaults, exact source resolution, self-overlay rejection, missing inputs, and original-snapshot isolation.
- Exact/glob deduplication, unsigned UTF-8 ordering, zero matches, and generated-pack exclusion.
- Unknown modern syntax rejects the whole definition while understood optional applicability can skip.
- Existing explicit format 1/2 and legacy-root behavior remains unchanged.

### Conflict tests

- Concrete leaf effects, array membership/order effects, and no trace for skipped mutations.
- No within-definition conflicts.
- Same-pack, cross-pack, identical redundant, and material overlap classifications.
- Report-only output equivalence.
- Later-definition `Allow` and target-local `Reject` behavior.

### Reload tests

- Debounce coalescing and one dirty follow-up epoch.
- Invalid-edit last-known-good preservation.
- Generated-event feedback-loop prevention.
- Election fencing.
- Store, key, path, provider, epoch, and disk-hash correlation.
- Removal confirmation.
- Watcher-disabled, overflow, stopped-monitor, mismatch, failure, timeout, and Common-asset fallbacks.

### Required commands

- Run `./mvnw.cmd test` after code changes.
- Run `./mvnw.cmd -pl standalone -am verify` after standalone packaging changes.
- Validate native Hytale lifecycle, codec, and schema usage against the indexed release API.

## Success Criteria

- Authors can use every new capability in the native Asset Editor without choosing or understanding a format version.
- Matching operations behave deterministically against pre-operation snapshots.
- Cross-asset operations never consume generated or patched output and never create target-order dependencies.
- Wildcard expansion is explicit, deterministic, and reevaluated when its inventory can change.
- Conflict reports describe actual effects and report-only mode does not alter generated bytes.
- Failures remain target-local and invalid edits preserve last-known-good output.
- Source assets and the generated pack never feed back into generation inputs.
- Patchwork never reports hot reload without correlated Hytale evidence; uncertain cases clearly require restart.
- Portable JSON remains editor-independent and lossless for existing accepted documents.

## Hytale Runtime Evidence

The live-reload design is based on the indexed Hytale 0.5.7 API and implementation behavior available during design:

- registered stores are discoverable through `AssetRegistry.getStoreMap()` and expose store paths;
- directory-pack stores install mutable monitors, while archive/hash packs are immutable;
- asset monitor events expose store, pack, and changed paths before load processing;
- loaded and removed asset events expose enough type/key data to correlate with retained store and pre-load path information;
- the winning asset pack and asset path can be inspected after load through the store's asset map; and
- store-registration lifecycle events allow generated directories to be prepared for later dynamic stores.

These observations justify the proposed confirmation and directory-preparation approach, but implementation must revalidate exact APIs against the Hytale dependency used by the repository at that time.
