# HyCreator × Patchwork Endgame Authoring Design

**Status:** Approved design
**Date:** 2026-08-02
**Products:** HyCreator and Patchwork

## Summary

HyCreator will provide a complete visual authoring environment for Patchwork definitions. A user can open an asset supplied by vanilla, another mod, or their own workspace; create a patch owned by a writable workspace; edit the result through HyCreator's existing Form and Nodes interfaces; validate and simulate it; and save the resulting definition below `Server/Patchwork/Patches` without writing raw JSON.

The target asset and the patch owner are separate concepts. HyCreator never copies or overwrites the target merely because it is being patched, and it never edits Patchwork's generated pack. The portable Patchwork definition remains independent of HyCreator metadata; exact evaluation can still depend on target packs, environment data, ModData documents, and macro providers. Optional HyCreator sidecar data preserves authoring history and visual layout without changing runtime semantics.

This is the complete product scope. The delivery slices at the end organize implementation but do not exclude later capabilities from the design.

## Goals

- Make every standard Patchwork operation authorable without raw JSON.
- Let a patch target an asset from any resolved pack while storing the definition in the user's own mod.
- Reuse HyCreator's schema-aware Form and Nodes editors instead of introducing a second content editor.
- Explain operations, conditions, compatibility, and runtime outcomes in user language.
- Support complex object graphs, NPC interaction chains, arrays, and multiple targets.
- Detect upstream target changes and help users rebase affected operations.
- Keep HyCreator's implementation native to its Tauri/Rust architecture.
- Establish a versioned, language-neutral Patchwork authoring contract shared by Java and Rust tests.

## Non-goals

- HyCreator does not become a Patchwork runtime.
- HyCreator does not write into `Alechilles:Patchwork_GeneratedPatches`.
- Patchwork does not own HyCreator-specific visual state.
- Connected-server deployment is not required to create, edit, validate, or save a definition.
- Raw JSON is never required for ordinary authoring, though an optional technical preview may be offered to advanced users.

## Terminology

- **Target asset:** The asset path whose JSON Patchwork modifies.
- **Resolved provider:** The currently winning source pack for the target path.
- **Patch owner:** The writable HyCreator workspace that contains the definition.
- **Patchwork baseline:** The raw JSON supplied by the highest-priority non-generated provider, resolved with Patchwork's source ordering before any eligible definition is applied.
- **Effective value:** A value HyCreator displays after Hytale inheritance or schema defaults; it may not exist in the raw baseline.
- **Patch Mode:** HyCreator's editing mode that preserves the baseline and records edits as Patchwork operations.
- **Draft:** The in-memory authoring representation of a definition or linked definition group.
- **Linked definition group:** Several emitted Patchwork files presented as one conceptual patch when per-target differences prevent a valid shared definition.

## Product principles

1. **Edit intent, not patch syntax.** Users say “set this field,” “insert after this interaction,” or “replace the item matching this identity.”
2. **Show both sides.** Target/provider and patch owner/destination remain visible during creation and saving.
3. **Preserve the source.** Patch Mode never mutates the baseline asset.
4. **Fail closed.** Ambiguous matches, missing anchors, invalid results, and unsafe paths block saving or application unless an operation is explicitly optional.
5. **Preview with explicit scope.** Validation distinguishes isolated-draft application from full known runtime-order composition, then validates the resulting asset when all required inputs are available.
6. **Report runtime truth.** Generated on disk, live-reloaded, and restart-required are distinct outcomes.
7. **Remain independent of HyCreator metadata.** The definition does not depend on a HyCreator sidecar. Exact runtime evaluation may additionally depend on target packs, pack order, environment data, ModData documents, and macro providers.

## End-to-end workflow

### 1. Select a target

The user opens any asset visible in HyCreator's resolved catalog. The asset may come from:

- Hytale/vanilla;
- another installed mod or content pack;
- the active workspace; or
- another writable workspace.

HyCreator shows the normalized asset path, resolved provider ID, provider version when available, and whether the provider is writable.

If HyCreator's general catalog currently shows `Alechilles:Patchwork_GeneratedPatches` as the visible provider, Create Patch resolves and displays the underlying non-generated Patchwork baseline instead. Generated output is never offered as the source provider for a new patch.

### 2. Create a patch

The primary entry point is **Create Patch** in the asset toolbar. Field and node context menus may also start a draft when none exists.

The creation dialog asks for:

- friendly patch name;
- patch owner, defaulting to the active writable workspace;
- generated stable ID, editable in an advanced section;
- definition path, defaulting below `Server/Patchwork/Patches`;
- priority, default `0`;
- enabled state, default `true`; and
- a recommended provider guard when the target comes from another mod.

The dialog separately labels:

- **Target asset** — read-only source/provider and asset path.
- **Patch owner** — writable workspace and destination definition path.

Creating the patch does not copy the target into the owner.

### 3. Author in Patch Mode

Patch Mode opens the target with:

- the baseline held read-only;
- the patched result editable through Form or Nodes;
- owned, inherited/unset, draft-modified, and conflicted values visually distinguished; and
- a compact right-hand Patch Draft navigator.

Small scalar operations can be edited directly in the sidebar. Objects, arrays, and graphs appear as summaries and open in the full central Form or Nodes canvas. The sidebar remains a table of contents, not a compressed graph editor.

### 4. Validate and simulate

HyCreator provides two explicit previews:

- **Isolated draft** applies only the current draft to the Patchwork baseline.
- **Composed runtime-order** replaces definitions originating from the edited owner and relative definition path with the draft, then applies every known eligible definition for the target using the total comparator: priority ascending, patch ID ascending by unsigned UTF-8 bytes, source-pack load order ascending, then source-pack ID ascending by unsigned UTF-8 bytes.

Composed preview uses Patchwork's discovery contract for neutral and eligible legacy roots, enabled definitions, neutral shadowing, duplicate rejection, and generated-pack exclusion. The Patchwork baseline excludes `Alechilles:Patchwork_GeneratedPatches`, even if HyCreator's general asset catalog currently displays that generated pack as the winning visible asset. Source resolution must use Patchwork's exact descending ordering: source-pack load order, then unsigned UTF-8 source-pack ID bytes as the deterministic tie-break. HyCreator shows:

- plain-English operation summaries;
- before/after values;
- resolved matches and anchors;
- condition truth for the selected environment;
- patched asset validation results; and
- compatibility warnings.

### 5. Save

HyCreator serializes a portable Patchwork definition and writes it atomically to the owner workspace. It never writes source assets or generated output.

### 6. Reopen and maintain

Definitions appear as first-class documents in a **Patches** section of the owning workspace. Opening one resolves the current Patchwork baseline, loads all known co-targeting definitions, replaces the saved current definition with the editable draft, and restores the target-plus-overlay experience. If the exact source inventory, order, macro provider, or condition environment is unavailable, composed preview is marked indeterminate rather than presented as runtime-equivalent.

### 7. Deploy and reload

Saving is independent of deployment. When HyCreator is connected and authorized, it may:

1. deploy the owning workspace through its normal deploy flow;
2. request `/patchwork reload` through a server bridge; and
3. show Patchwork's actual target outcomes.

`restart-required` means the generated state is committed on disk but is not live. HyCreator must not present it as a live success.

## Patch Draft interface

Each draft card contains:

- operation icon and plain-language name;
- affected field, section, array, or graph;
- concise before/after or match summary;
- validation state;
- required/optional state in advanced controls;
- open-in-Form or open-in-Nodes action for complex values;
- reorder handle, because operation order is semantic; and
- remove/disable action.

The draft header contains patch name, target count, health, undo/redo, settings, and Save Patch. The full editor offers **Compare with source**, **Preview result**, and **Return to target**.

## Automatic operation inference

Inference is based on the raw baseline, not only the effective inherited view.

| User intent | Emitted operation |
| --- | --- |
| Change an existing object property or array entry | `Replace` |
| Set a property missing from an existing parent | `Add` |
| Add or edit several properties in an existing object | `Merge` when it preserves intent; otherwise leaf operations |
| Clear an existing property or array entry | `Remove` |
| Append or add relative to an array anchor | `Insert` |
| Replace an array entry by stable identity | `ReplaceMatching` |
| Remove array entries by stable identity | `RemoveMatching` |
| Reorder an array entry by stable identity | `MoveMatching` |

### Inherited and unset values

- Setting an effective value absent from raw JSON emits `Add`, not `Replace`.
- If intermediate objects are absent, HyCreator adds or merges the nearest safe subtree whose parent exists.
- Removing a purely inherited value is disabled because no property exists in the raw target. HyCreator may offer a schema-supported explicit override instead, but it must not invent null/unset semantics.

### Context menus

Context menus expose intent only where automatic inference would be ambiguous:

- Patch this field or section.
- Set/override value.
- Remove owned value.
- Insert at start/end/before/after.
- Replace matching item.
- Remove matching item.
- Move matching item.
- Choose stable identity fields.
- Mark operation optional.

Users select array entries and anchors visually. HyCreator generates JSON Pointer escaping, recursive matchers, duplicate guards, and operation values.

## Array matcher experience

When creating a matcher operation, HyCreator proposes stable identity fields such as `Id`, `Type`, `Name`, or another schema-recognized key. The user sees a rule builder and current match count.

Format version 2 defines a strict, language-neutral matcher grammar. Every matcher is a non-empty JSON object; `{}` is structurally invalid. A matcher is exactly one of:

- `{ "$Equals": value }` matches any candidate JSON value by exact equality, including primitive array entries.
- `{ "$Contains": matcher }` matches an array candidate containing at least one entry that satisfies the nested matcher.
- An ordinary object matcher recursively matches a candidate object against the non-empty subset of fields declared by the matcher.

A reserved matcher operator occupies the whole matcher object and cannot be combined with sibling fields. Reserved keys cannot be interpreted as ordinary asset field names; exact matching through `$Equals` remains available for objects that contain such keys. An ordinary field whose expected value is an object uses recursive subset matching; primitive, null, and array expected values use exact equality.

Exact JSON equality is type-aware:

- strings compare as exact Unicode scalar sequences;
- booleans and null compare only with the same JSON type;
- numbers compare by arbitrary-precision mathematical decimal value, so `1`, `1.0`, and `1e0` are equal without binary floating-point loss;
- arrays require equal length, order, and recursively equal entries; and
- objects require the same key set and recursively equal values, independent of key order.

Format version 2 rejects duplicate keys in every JSON object before model parsing. Match selection always uses an immutable snapshot taken immediately before that operation. `Insert.Find` retains its documented first-match behavior, and `Insert.Existing` skips when any entry matches. New matching operations use their explicit policies; `MoveMatching.Find` must be unique.

This contract applies consistently to `Insert` anchors and duplicate guards as well as the new matching operations. Legacy format version 1 retains its documented compatibility behavior and has a separate conformance fixture set.

The default match policy is **Exactly one**:

- zero matches fail;
- one match succeeds; and
- multiple matches fail as ambiguous.

`ReplaceMatching` and `RemoveMatching` additionally allow **First**, **Last**, and **All**. The UI always displays how many current entries each policy affects.

`MoveMatching` requires exactly one item and, for before/after placement, exactly one anchor. An item cannot use itself as its anchor.

## Conditions

The Advanced Conditions panel renders conditions as readable nested cards:

- installed mod;
- mod version;
- server/game version;
- asset exists or is missing;
- target exists;
- target provided by an exact source pack;
- JSON path exists;
- JSON path equals;
- all;
- any; and
- not.

The builder offers condition, AND group, OR group, and NOT actions. It continuously evaluates the condition against a selectable environment snapshot and explains failures without exposing sensitive mod-data values.

When the target comes from another mod, HyCreator recommends `TargetProvidedBy` and, when relevant, a compatible `ModVersion` condition. `ModInstalled` alone does not guarantee that the mod currently provides the winning target.

## Multi-target authoring

Users can select compatible targets from the catalog or add them to an existing draft. HyCreator simulates every operation independently for every target and displays a matrix containing:

- target path and resolved provider;
- condition result;
- applicable operation count;
- matcher/anchor result;
- patched asset validation; and
- final safe, warning, or failed state.

HyCreator emits one definition with `Targets` only when every target shares identical:

- ordered operations and values;
- condition;
- priority;
- enabled state; and
- requiredness.

If a target needs a different anchor, value, condition, or skipped operation, HyCreator offers to resolve it or split it into a linked definition. Linked definitions remain grouped in HyCreator but are independent portable files.

## Patchwork format contract

### Format versioning

Patchwork adds `FormatVersion` at the definition root.

- Missing means legacy format version 1.
- When present, it is a positive integer.
- Unknown future versions are rejected before application by runtimes that understand format versioning.
- HyCreator declares format version 2 when using the extensions in this design.
- Every valid format-2 definition begins with the mandatory `RequireFormat` sentinel described below. Format-1 runtimes reject that unknown required operation before publishing the target, so format-2 changes to legacy matcher and pointer semantics cannot be silently misapplied.

Format-2 schema objects are closed with `additionalProperties: false`, including the definition root, operations, conditions, version matchers, and condition source descriptors. Arbitrary JSON is allowed only in explicit data containers such as operation `Value`, matcher data, expected condition values, and macro `Options` as constrained by an available macro descriptor. This prevents misspellings such as `Priorty` from being silently ignored.

An unknown-version document is inspect-only in HyCreator. HyCreator retains its original bytes and may leave an unchanged file untouched, but it cannot simulate, edit, normalize, or save the parsed document until an explicit migration to a supported version succeeds.

### JSON Pointer contract

Format version 2 uses RFC 6901 for operation and condition pointers:

- the empty pointer addresses the document root;
- `/` addresses an object property whose name is empty;
- `~0` and `~1` decode to `~` and `/`, and any other `~` escape is invalid;
- array indexes use `0` or a non-zero digit followed by digits, with no sign or leading zero;
- an array index must fit a non-negative 32-bit integer and be in bounds for the operation;
- `-` is accepted only as the final `Add` token for array append; and
- operations continue to reject the document root as a mutation target, while conditions may inspect it.

Patchwork aligns operation and condition parsing to this contract before publishing the shared format-2 fixtures. Legacy format-1 pointer behavior is preserved only through its compatibility fixture set.

### Operation structure and failure phases

Operations ordinarily use the common fields `Id`, `Op`, and `Required`; the compatibility sentinel deliberately forbids `Required`. Format version 2 applies this field matrix to the new operations:

| Operation | Required fields | Optional fields | Forbidden operation fields |
| --- | --- | --- | --- |
| `RequireFormat` | `Op`, `Version` | `Id` | `Required`, `Path`, `Value`, `Position`, `Match`, `MatchPolicy`, `Find`, `Existing`, `Macro`, `Options` |
| `ReplaceMatching` | `Op`, `Path`, `Match`, `Value` | `Id`, `Required`, `MatchPolicy` | `Position`, `Find`, `Existing`, `Macro`, `Options` |
| `RemoveMatching` | `Op`, `Path`, `Match` | `Id`, `Required`, `MatchPolicy` | `Value`, `Position`, `Find`, `Existing`, `Macro`, `Options` |
| `MoveMatching` | `Op`, `Path`, `Match` | `Id`, `Required`, `Position`; `Find` only for `Before`/`After` | `Value`, `MatchPolicy`, `Existing`, `Macro`, `Options` |

For these operations, any operation-level field not listed as required or optional is forbidden. Missing required fields, forbidden fields, malformed or empty matchers, invalid policies/positions, `Find` on `Start`/`End`, and `Before`/`After` without `Find` are structural errors. A structural error rejects the entire definition file, including all targets expanded from `Targets`; `Required: false` cannot suppress it.

Applicability errors arise only after a structurally valid operation is applied to a concrete target. They include a missing/non-array path, zero or disallowed multiple matches, missing/non-unique move anchor, and self-anchor. Applicability errors use the existing `Required` contract: required rejects the affected target, while optional reports a skipped operation and continues.

### `RequireFormat`

```json
{ "Op": "RequireFormat", "Version": 2 }
```

Every format-2 definition contains exactly one `RequireFormat` as its first operation, and its integer `Version` must equal the root `FormatVersion`. `Required` is forbidden so a format-1 runtime uses its existing default of `true`, rejects the unsupported operation, and publishes no partially modified target. A format-2 runtime validates the sentinel and records it as an applied no-op before processing later operations. HyCreator generates and hides this compatibility sentinel automatically.

### `ReplaceMatching`

```json
{
  "Id": "replace-tail-spin",
  "Op": "ReplaceMatching",
  "Path": "/Interactions",
  "Match": { "Id": "Tail_Spin_Damage" },
  "MatchPolicy": "ExactlyOne",
  "Value": { "Id": "Tail_Spin_Damage", "Type": "Interaction" }
}
```

Rules:

- `Path` must resolve to an existing array.
- `Match` is required and uses the recursive matcher semantics already established by `Insert`, including `$Contains`.
- `Value` is required.
- `MatchPolicy` is case-insensitive and defaults to `ExactlyOne`.
- `First`, `Last`, and `All` select the lowest index, highest index, or every matching index.
- Zero matches fail for every policy.
- Replacement preserves array order and deep-copies `Value` into each selected position.

### `RemoveMatching`

```json
{
  "Id": "remove-deprecated-roar",
  "Op": "RemoveMatching",
  "Path": "/Interactions",
  "Match": { "Id": "Deprecated_Roar" },
  "MatchPolicy": "All"
}
```

Rules match `ReplaceMatching`, except no `Value` is accepted. When removing multiple entries, Patchwork removes selected indexes from highest to lowest so index shifts cannot change the selection.

### `MoveMatching`

```json
{
  "Id": "move-bite-after-stomp",
  "Op": "MoveMatching",
  "Path": "/Interactions",
  "Match": { "Id": "Bite_Damage" },
  "Position": "After",
  "Find": { "Id": "Stomp_Damage" }
}
```

Rules:

- `Path` must resolve to an existing array.
- `Match` must identify exactly one entry.
- `Position` is case-insensitive and defaults to `End`; valid values are `Start`, `End`, `Before`, and `After`.
- `Before` and `After` require `Find`, which must identify exactly one different anchor entry.
- `Find` is structurally forbidden for `Start` and `End`.
- Patchwork identifies item index `m` and, when needed, anchor index `a` against the same pre-move array. It removes the item, computes the anchor's post-removal index as `a - 1` when `m < a` and `a` otherwise, then inserts at that index for `Before` or one position after it for `After`. `Start` inserts at index `0`; `End` inserts at the post-removal array length.
- Matching the moving item as its own anchor fails.
- If the calculated insertion recreates the existing order, the operation succeeds as a no-op and is reported as skipped with the diagnostic `already in requested position`.

### Requiredness

All new operations retain the existing `Required` behavior. A required failure rejects the affected target. An optional failure is reported as skipped and later operations continue.

### Total definition ordering

Patchwork makes definition ordering total: priority ascending, patch ID ascending by unsigned UTF-8 bytes, source-pack load order ascending, then source-pack ID ascending by unsigned UTF-8 bytes. Strings are compared exactly without Unicode normalization; format-2 parsing rejects invalid Unicode scalar sequences. The same unsigned UTF-8 rule applies to source-pack ID tie-breaks during baseline resolution. Same-root duplicate rules continue to reject definitions that would share source-pack ID, patch ID, and target. Java runtime ordering, HyCreator composition, documentation, and shared fixtures use the same comparator, including equal-load-order definitions from different packs.

### `TargetProvidedBy`

```json
{ "TargetProvidedBy": "Example:DragonsPlus" }
```

The condition compares its value with the exact, case-sensitive winning `ResolvedTarget.sourcePackId`. Generation passes the same already-resolved target snapshot, including provider identity, into condition evaluation; it never performs a second resolution that could observe different pack state. Conditions are evaluated only after successful target resolution, so a missing target remains a target-resolution failure and never reaches `TargetProvidedBy`. For a resolved target, provider mismatch is `NOT_MATCHED`, provider match is `MATCHED`, and `All`, `Any`, and `Not` retain their existing composition behavior.

## Official Patchwork authoring kit

Patchwork publishes a versioned, language-neutral authoring kit with each format release:

- JSON Schema for definition structure;
- semantic specification for pointers, ordering, matching, requiredness, and conditions;
- valid definition fixtures;
- invalid definition fixtures with expected diagnostic categories;
- source-definition-result fixtures for every operation and match policy;
- condition fixtures and environment inputs;
- multi-definition ordering fixtures; and
- a capability document listing supported format versions, operations, conditions, and macro descriptor version.

Patchwork's Java tests and HyCreator's Rust tests consume the same conformance corpus. JSON Schema alone is not treated as proof of application semantics.

## Macro authoring descriptors

Runtime macro providers remain authoritative. A host may optionally ship editor metadata below:

```text
Server/Patchwork/Authoring/Macros/**/*.json
```

Each descriptor declares:

- descriptor format version;
- macro ID;
- label and description;
- options JSON Schema; and
- optional editor hints such as control labels, ordering, asset-picker categories, and Form/Nodes affordances.

HyCreator uses descriptors to render typed option forms. Descriptors and editor hints are untrusted asset-pack data: they may select only documented controls and asset-picker categories, and cannot execute scripts, load remote content, or request filesystem access. If no descriptor exists, HyCreator preserves the macro as an opaque operation card. It does not discard or rewrite unknown options. Offline macro expansion is not promised; a connected compatible provider may optionally supply a preview.

## HyCreator architecture

HyCreator is a Tauri 2 application with a Rust backend and WebView2 frontend. It should not embed Patchwork's Java/Gson runtime packages, which are internal and coupled to the Hytale runtime module.

```text
HyCreator web frontend
  - Patch Mode presentation
  - draft navigator
  - Form/Nodes interactions
  - condition and matcher builders
          |
          | typed draft commands/results
          v
HyCreator Rust authoring module
  - format model and preservation of opaque nodes
  - path and matcher validation
  - local operation engine
  - condition evaluation against supplied snapshots
  - per-target simulation
  - deterministic serialization
  - atomic workspace writes
  - sidecar and rebase analysis
          |
          +--> portable Patchwork definition
          +--> optional HyCreator sidecar
```

The Rust module is tested against Patchwork's conformance kit. Filesystem writes remain in Rust rather than browser code.

Runtime-equivalent composed simulation additionally requires the exact non-generated pack inventory and order, target bytes, installed IDs and versions, server version, referenced Asset and ModData documents, and compatible macro providers. These are explicit environment dependencies, not contents supplied by the HyCreator sidecar.

### Round-trip preservation

HyCreator must open hand-authored and future definitions safely:

- known fields become typed controls;
- a document with an unknown `FormatVersion` is inspect-only and retains its original bytes until explicitly migrated;
- a known-version document containing an unknown or forbidden structural field, operation, or condition is schema-invalid and inspect-only until corrected or explicitly migrated;
- explicit data containers such as asset `Value`, matcher data, expected values, and macro `Options` round-trip with semantic JSON preservation, not preservation of whitespace, object-key order, or numeric spelling;
- an unknown macro ID remains a structurally known `Macro` operation whose `Options` are preserved opaquely;
- duplicate object keys are rejected rather than normalized;
- schema-invalid content is never presented as a valid editable definition.

HyCreator can claim byte-exact preservation only when it retains and leaves the original unmodified bytes untouched. Deterministic serialization applies to fully supported known-version documents and semantically preserved explicit data containers, not arbitrary source formatting.

## Optional HyCreator authoring metadata

The runtime definition is authoritative. HyCreator may keep a sidecar outside scanned Patchwork roots, for example:

```text
<workspace>/.hycreator/patchwork/<definition-path-hash>.json
```

The sidecar contains only authoring assistance:

- definition relative path and stable patch ID;
- linked definition group ID;
- resolved provider ID and version at authoring time;
- target and touched-fragment hashes;
- previous values for touched paths;
- matcher and anchor fingerprints;
- node layout and editor view state; and
- last successful local validation summary.

The sidecar is excluded from deployed asset content. If it is missing, HyCreator reconstructs the document from the definition and current target, with reduced historical rebase context.

## Validation pipeline

Validation runs before save and can also run continuously:

1. **Schema validation:** Definition shape, field types, supported format, and condition structure.
2. **Path safety:** Normalized forward-slash target and definition paths; safe JSON Pointers.
3. **Identity checks:** Stable patch/operation IDs and collision checks within the owner/root/target scope.
4. **Target resolution:** Current winning provider, bytes, and availability.
5. **Condition evaluation:** Truth and evaluation diagnostics for the chosen environment.
6. **Ordered dry run:** Apply operations exactly in runtime order for each target.
7. **Matcher analysis:** Match counts, anchor uniqueness, duplicate prevention, and policy effects.
8. **Asset validation:** Validate patched results through HyCreator's descriptor-aware validator.
9. **Compatibility analysis:** Provider drift, target fragment drift, numeric-index sensitivity, and missing guards.
10. **Serialization verification:** Parse serialized output again and reproduce the same local result.

Validation reports one of three top-level semantic states:

- **Validated:** All required inputs were available and every stage succeeded.
- **Invalid:** Available evidence proves a structural, applicability, condition, or asset-validation failure.
- **Indeterminate:** Required external inputs are unavailable or unsupported, including incomplete pack order, unreadable referenced ModData, or a macro that cannot be expanded locally.

Condition `NOT_MATCHED` is a valid, non-applicable result for the selected environment and causes the definition to be previewed as skipped; it is not `Invalid`. Condition `FAILED` is `Invalid` when available evidence proves an evaluation error, or `Indeterminate` when HyCreator lacks an external input needed to reproduce the runtime evaluation.

Structural errors always prevent the atomic write. Proven semantic errors prevent save unless the user returns to the draft and resolves or explicitly makes the relevant operation optional. An indeterminate definition may be saved after clear acknowledgement, but HyCreator never labels it locally validated or runtime-equivalent. Save uses a temporary sibling file and replacement/rename so a failed write does not truncate the previous definition.

## Patch health and rebasing

Opening a patch compares current inputs with sidecar history when available. Each operation is classified as:

- safe and unchanged;
- safe despite unrelated target changes;
- changed but still uniquely applicable;
- ambiguous;
- missing path, matcher, or anchor;
- condition changed; or
- unsupported/opaque.

Conflict actions include:

- accept a suggested path or anchor;
- choose a new path, matcher, or anchor visually;
- edit the operation value in Form or Nodes;
- split one target from a linked group;
- mark the operation optional;
- disable/remove the operation; and
- keep the conflict unresolved without rewriting the file.

Automatic suggestions never save without user confirmation.

## Server bridge and truthful outcomes

The core authoring feature has no server dependency. A separate `PatchworkServerBridge` may expose:

- capability/status query;
- authorized reload request;
- per-target reload result; and
- generated inventory/epoch summary.

The transport is supplied by HyCreator's existing server integration. Patchwork should provide a machine-readable administration representation rather than requiring HyCreator to parse human command text.

HyCreator maps Patchwork outcomes directly:

- generated;
- removed;
- hot-reloaded;
- adapter-reloaded;
- restart-required;
- stale;
- rollback-failed;
- skipped; and
- failed.

Generated file presence alone never means the running server consumed the patch.

## Diagnostics and error presentation

Diagnostics identify:

- affected target and provider;
- patch and operation ID;
- category and severity;
- plain-language explanation;
- relevant field, matcher, or anchor; and
- available repair action.

Sensitive mod-data values, expected secret-bearing values, filesystem internals, and rollback bytes remain hidden in accordance with Patchwork's existing status contract.

## Testing strategy

### Shared conformance

- Both implementations execute the official operation, matcher, pointer, ordering, and condition fixtures.
- New matcher fixtures cover zero, one, multiple, nested, `$Contains`, first, last, all, self-anchor, and index-shift cases.
- Equality fixtures cover null, Unicode strings, booleans, exact large/exponent decimals, arrays, object key-order independence, reserved matcher keys, empty matchers, and duplicate JSON-key rejection.
- Pointer fixtures cover the empty pointer, `/`, empty-key properties, valid and invalid `~` escapes, append `-`, leading zeros, signs, overflow, and bounds.
- Provider fixtures cover winning-pack resolution and `TargetProvidedBy` composition.
- Preview fixtures distinguish isolated drafts from full runtime-order composition and exclude the generated pack from baseline selection.
- Compatibility fixtures prove a format-1 runtime rejects a valid format-2 definition at the mandatory `RequireFormat` sentinel before publishing output.
- Ordering fixtures cover equal priority, patch ID, and source-pack load order across different source-pack IDs.
- Closed-schema fixtures cover misspelled and extraneous fields at every format-owned object layer.

### Patchwork

- Parser and format-version compatibility tests.
- Engine tests for all new operations and required/optional behavior.
- Target-provider condition tests.
- Scanner/generation tests proving target-local failure isolation.
- Schema and fixture publication tests.

### HyCreator

- Rust serializer and dry-run engine conformance tests.
- Golden tests for deterministic definition output and opaque-field preservation.
- Inspect-only tests for unknown format versions and schema-invalid known-version operations/conditions.
- Indeterminate-state propagation tests for unavailable external ModData and macro expansion.
- UI behavior tests for Create Patch, operation inference, matcher selection, condition composition, and target/owner labeling.
- Multi-target split tests.
- Create/save/reopen and upstream-change/rebase workflows.
- Atomic-write failure test proving the previous definition remains intact.
- Server outcome mapping tests without requiring a live Hytale server.

## Delivery slices

These slices allow independent delivery while preserving the complete scope.

1. **Patchwork authoring foundation**
   - Format versioning, JSON Schema, capability document, shared fixtures, `TargetProvidedBy`, and matcher/move operations.
2. **HyCreator authoring core**
   - Rust model/engine, target-versus-owner creation flow, Patch Mode, draft sidebar, scalar/object operations, validation, and atomic save.
3. **Complex structure authoring**
   - Full Form/Nodes operation editing, Insert, matcher operations, move, duplicate guards, and visual match analysis.
4. **Advanced applicability**
   - Full condition builder, provider guards, multi-target matrix, and linked definition splitting.
5. **Interoperability and extensibility**
   - Opaque round-tripping, macro descriptors, capability negotiation, and technical preview.
6. **Maintenance lifecycle**
   - Sidecars, patch health, upstream drift analysis, visual rebasing, and conflict repair.
7. **Deployment lifecycle**
   - Server bridge, deploy/reload actions, and truthful per-target runtime outcomes.

## Acceptance criteria

The endgame is complete when:

- a user can patch a vanilla or third-party asset into their own workspace without copying the source;
- every supported operation and condition can be authored visually;
- large graphs use the full Form/Nodes workspace rather than a cramped sidebar;
- array entries can be inserted, replaced, removed, and moved using stable visual matchers;
- multiple targets are simulated independently and split when semantics diverge;
- saved and hand-authored definitions reopen without data loss;
- upstream changes produce actionable patch-health diagnostics and visual rebase tools;
- Java and Rust pass the same conformance corpus;
- saving never mutates source or generated packs;
- local generation and live server application are reported as different states; and
- the produced Patchwork definition remains usable without HyCreator metadata.
