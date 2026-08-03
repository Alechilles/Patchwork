# Typed Patchwork Authoring Design

**Date:** 2026-08-03  
**Status:** Approved for implementation planning

## Goal

Make Patchwork's native Hytale asset type substantially easier for beginners by giving `When`, matcher, and arbitrary JSON fields guided recursive schemas while preserving the exact documented portable JSON syntax.

The Asset Editor should help authors choose condition forms, build nested matchers, and enter JSON values without memorizing the grammar. Files saved or reopened through the native asset type must remain ordinary Patchwork definitions that work with the existing runtime, external editors, and the versioned authoring kit.

## Compatibility Contract

This work must not introduce an editor-only discriminator or wrapper field. In particular, it must not add a shared `Type` property to conditions or JSON values.

The following remain authoritative and unchanged:

- The portable JSON shapes documented in `docs/Patch-Format.md`.
- Duplicate-aware parsing by `PatchDefinitionReader`.
- Runtime validation by `PatchDefinition.parseAll`, `PatchConditionParser`, `PatchOperation`, and `JsonMatcher`.
- Lossless `portableSource` preservation for accepted legacy extensions, unknown legacy operation data, JSON `null`, and high-precision numbers.
- The scanner as the sole source of definitions used for generation.

The generated native schema describes the supported authoring contract. Existing legacy documents may contain data that the modern schema does not suggest, but opening and saving an accepted legacy document must not delete or normalize that data.

## Recommended Architecture

Use transparent schema codecs backed by the existing BSON values. These codecs improve `toSchema(...)` while delegating decode and encode without converting data into a parallel Java object graph.

This approach is preferred over full typed Java models because it avoids a second parser, avoids precision conversion, and cannot silently drop host-defined or legacy data.

### `PatchConditionCodec`

`PatchConditionCodec` remains a `Codec<BsonDocument>`. BSON decode and encode are pass-through operations. Its generated schema describes the exact one-key condition grammar, including optional `$Comment`.

The schema covers:

- `ModInstalled`
- `ModVersion`
- `ServerVersion` and its `GameVersion` alias
- `AssetExists` and `AssetMissing`
- `TargetExists`
- `TargetProvidedBy`
- `JsonPathExists`
- `JsonPathEquals`
- recursive `All`, `Any`, and `Not`

Nested source descriptors are typed as `Target`, `Asset`, or `ModData`. Version comparison objects expose only the supported comparison fields. Asset references retain both accepted portable forms: a string or an object containing `Asset`.

Condition variants are represented as titled schema alternatives using their existing property names. No artificial discriminator is serialized. If the Hytale Asset Editor cannot render a dedicated selector for a property-discriminated union, it should expose the finite, documented condition properties as creation choices; the implementation must not compromise portable JSON to force a dropdown.

### `PatchMatcherCodec`

`PatchMatcherCodec` also remains BSON-backed. Its recursive schema represents the three valid matcher forms:

1. A sole `$Equals` property whose value is any JSON value.
2. A sole `$Contains` property whose value is another matcher.
3. A non-empty ordinary object whose non-`$` property names map recursively to matcher values.

Matcher values may be scalar JSON values, arrays of exact JSON values, or nested matchers. The schema must keep ordinary property names open because they come from the target asset. Runtime validation remains responsible for enforcing sole-key reserved operators and rejecting empty or malformed matchers.

The same codec is used for `Match`, `Find`, and `Existing`, with field-specific tooltips explaining their different operation semantics.

### `PatchJsonValueCodec`

`PatchJsonValueCodec` retains pass-through `BsonValue` storage but replaces its untyped schema with a recursive union of:

- `null`
- boolean
- number
- string
- array of JSON values
- object whose properties contain JSON values

A companion object-only schema is used where the portable contract requires an object, including macro `Options` and object-shaped condition/source descriptors.

This schema must not coerce numbers through `double`, `long`, or Gson primitives. BSON values and the original portable source remain the serialization representation.

## Recursive Schema Construction

Recursive condition, matcher, and JSON-value schemas must use Hytale `SchemaContext` references rather than constructing an infinitely nested schema tree.

The implementation should expose stable named schema definitions for:

- `PatchCondition`
- `PatchMatcher`
- `PatchMatcherValue`
- `PatchJsonValue`
- `PatchJsonObject`

Hytale 0.5.7's `SchemaContext.refDefinition(...)` supports named schemas by registering a placeholder before resolving the definition. The implementation should use that mechanism, or an equivalently guarded schema reference, so recursive `All`/`Any`/`Not`, `$Contains`, arrays, and objects terminate cleanly.

## Editor Experience

The beginner-facing flow is:

1. Add `When` and choose a documented condition form.
2. For compound conditions, add nested condition entries recursively.
3. Choose an operation using the existing `Op` enum.
4. For matching operations, build `Match`, `Find`, or `Existing` using ordinary fields, `$Equals`, or `$Contains`.
5. For `Value` and `Options`, choose or enter the required JSON shape and add nested fields or entries.
6. Receive tooltips explaining defaults, coupling rules, and common failure cases.

The conceptual editor may present union choices as dropdowns, cards, or property creation actions depending on Hytale's renderer. The required behavior is guided, finite choices where the grammar is finite and recursive typed entry where keys or values are intentionally open.

The JSON written to disk remains in the current form. For example, an `All` condition remains:

```json
{
  "When": {
    "All": [
      { "ModInstalled": "Example:Livestock" },
      { "TargetProvidedBy": "Example:Base" }
    ]
  }
}
```

No `Type`, `$Editor`, or wrapper metadata is added.

## Data Flow

```text
Hytale generated schema
        ↓ guides editing
portable JSON / BSON values
        ↓ decoded losslessly
PatchDefinitionAsset.portableSource
        ↓ validated by existing code
PatchDefinition.parseAll
        ↓ later discovered by
PatchScanner
```

The schema is advisory and structural. The existing parser remains the final authority for cross-field rules, exact format-version behavior, condition exclusivity, pointer syntax, matcher validity, and operation applicability.

## Error Handling

- Schema descriptions explain required companion fields such as `Find` with `Before`/`After` and `Version` with `RequireFormat`.
- Schema alternatives constrain obvious type and enum mistakes early.
- The existing portable parser produces the final validation failure for rules the schema cannot fully express.
- Invalid input must fail with the existing Patchwork diagnostic context; schema codecs must not replace those messages with lossy conversion failures.
- Existing valid files must continue to decode even when their formatting or accepted legacy extensions are not suggested by the generated schema.

## Implementation Order

Implementation follows three vertical slices so each usability improvement can be verified independently:

1. Typed `When` conditions and recursive composition.
2. Recursive `Match`, `Find`, and `Existing` matchers.
3. Recursive arbitrary JSON for `Value` and object-only `Options`.

Shared named-schema helpers may be introduced in the first slice when required for recursion, but unrelated runtime or format refactors are out of scope.

## Testing

Tests must cover both generated schema behavior and preservation of the portable contract.

### Schema tests

- Every supported condition appears with nonblank documentation.
- `All`, `Any`, and `Not` reference the recursive condition schema.
- Source descriptors expose `Target`, `Asset`, and `ModData` shapes.
- Matcher schemas expose `$Equals`, `$Contains`, and open ordinary keys.
- JSON-value schemas expose all six JSON kinds recursively.
- `Options` is object-only while `Value` accepts any JSON value.
- Recursive schemas terminate through references and can be serialized.

### Compatibility tests

- Representative format-2 conditions, matchers, and values round-trip unchanged.
- JSON `null`, large integers, and high-precision decimals remain exact.
- Existing accepted legacy extensions remain present after open/save.
- Lowercase accepted `Position` and `MatchPolicy` values remain lossless when reopening existing files.
- Duplicate-key and trailing-content rejection remains unchanged.
- Runtime parser corpus tests remain green.

### Verification

- Run the focused native authoring tests during each slice.
- Run `./mvnw.cmd test` after source changes.
- Run `./mvnw.cmd -pl standalone -am verify` when producing an installable standalone artifact.
- Validate new Hytale codec and schema references against the indexed release API.

## Documentation

Update `CHANGELOG.md` for the new native authoring experience. Update `README.md` or `docs/Patch-Format.md` only where the editor guidance changes; do not imply a new portable format or runtime feature.

## Non-Goals

- No Patchwork format 3.
- No editor-only JSON fields or normalization pass.
- No second condition, matcher, or operation parser.
- No target-asset-aware completion for arbitrary `Path`, matcher keys, or `Value` fields.
- No host-specific schema discovery for macro `Options`; Patchwork can only provide a documented arbitrary object unless a separate host-extension contract is designed later.
- No change to patch application, runtime election, reload behavior, or discovery.

## Success Criteria

- Beginners receive guided recursive editing for conditions, matchers, and JSON values.
- The saved file is valid, recognizable portable Patchwork JSON with no editor metadata.
- Existing accepted documents preserve their original values and extensions.
- Runtime behavior and validation ownership do not change.
- Generated schemas and full Maven verification pass against Hytale release 0.5.7.
