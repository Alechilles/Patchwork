---
title: "Field Reference"
order: 2
published: true
draft: false
---

# Field Reference

Parent: [Reference Library](/mod/patchwork/reference-library) | [Home](/mod/patchwork/home)

This page is a compact lookup for the portable marker-free definition format. For explanations and examples, use the linked authoring guides.

## Definition Root

| Field | Type | Notes |
| --- | --- | --- |
| `$Comment` | string | Optional author note; ignored by Patchwork. |
| `Id` | string | Optional stable patch ID. |
| `Target` | string | One exact asset path or `glob:` selector. Cannot appear with `Targets`. |
| `Targets` | array of strings | Non-empty unique exact paths/selectors. Cannot appear with `Target`. |
| `Priority` | integer | Lower runs first; defaults to `0`. |
| `Enabled` | boolean | Defaults to `true`. |
| `ConflictPolicy` | `Report`, `Allow`, or `Reject` | Neutral definitions only. |
| `When` | condition object | Optional eligibility test. |
| `Operations` | array | Required ordered list of operations. |

New neutral roots accept only these structural fields. Read [Patch Anatomy](/mod/patchwork/patch-anatomy) for why `FormatVersion` is not part of new work.

## Shared Operation Fields

| Field | Type | Notes |
| --- | --- | --- |
| `Id` | string | Optional operation ID. |
| `Op` | string | Required operation name. |
| `Required` | boolean | Defaults to `true`; makes an applicability failure a skip when `false`. |
| `Path` | JSON Pointer | Destination field, object, or array, where the operation needs one. |
| `Value` | JSON value | The value to add, merge, replace, or insert. |

Supported neutral operation names are `Add`, `Merge`, `Replace`, `Remove`, `Insert`, `ReplaceMatching`, `RemoveMatching`, `MoveMatching`, `MergeMatching`, `UpsertMatching`, `OverlayFromAsset`, `MergeObjectFromAsset`, and `Macro`.

## Operation-Specific Fields

| Field | Used by | Meaning |
| --- | --- | --- |
| `Find` | `Insert`, `MoveMatching`, `UpsertMatching` | Matcher used as an array anchor for `Before` or `After`. |
| `Existing` | `Insert` | Matcher that prevents inserting duplicate-like content. |
| `Position` | array insert/move/upsert | `Start`, `End`, `Before`, or `After`; defaults vary to `End`. |
| `Match` | matching operations | Object matcher selecting array entries. |
| `MatchPolicy` | matching operations | `ExactlyOne`, `First`, `Last`, or `All`. |
| `Source` | cross-asset operations | Exact source asset path. |
| `SourcePath` | `MergeObjectFromAsset` | Optional pointer inside `Source`; defaults to its root. |
| `Macro` | `Macro` | Host-provided macro ID. |
| `Options` | `Macro` | Host-defined macro data. |

## Condition Keys

One condition object has exactly one key, except that `$Comment` may accompany it.

| Key | Checks |
| --- | --- |
| `ModInstalled` | Exact mod ID is present. |
| `ModVersion` | Version bounds for an exact mod ID. |
| `ServerVersion` or `GameVersion` | Server/game version bounds. |
| `AssetExists` / `AssetMissing` | Exact asset presence. |
| `TargetExists` | The definition's current target is present. |
| `TargetProvidedBy` | The exact winning source-pack ID for the target. |
| `JsonPathExists` | A pointer exists in the target or selected source document. |
| `JsonPathEquals` | A pointer has the requested JSON value. |
| `All`, `Any`, `Not` | Boolean composition. |

See [Conditions: Apply Only When Needed](/mod/patchwork/conditions-apply-only-when-needed) for sources and examples.
