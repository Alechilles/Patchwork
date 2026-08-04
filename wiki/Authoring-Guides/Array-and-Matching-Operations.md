---
title: "Array and Matching Operations"
order: 5
published: true
draft: false
---

# Array and Matching Operations

Parent: [Authoring Guides](/mod/patchwork/authoring-guides) | [Home](/mod/patchwork/home)

Arrays are lists in JSON. Their order can change as Hytale or another mod updates an asset, so selecting an entry by what it contains is usually safer than selecting it by a numeric index.

## Match an Entry

`Match` is an object describing properties an array entry must have. This selects an entry with `Id` equal to `example`:

```json
{ "Match": { "Id": "example" } }
```

Declared nested properties must match too. `$Equals` requires complete JSON equality; `$Contains` checks whether an array has an element matching its nested matcher.

```json
{ "$Equals": { "Id": "example", "Rank": 1 } }
```

```json
{ "$Contains": { "$Equals": "MyMod" } }
```

## Decide How Many Matches Are Okay

`MatchPolicy` defaults to `ExactlyOne`, which is the safest choice: zero or more than one match fails. Use another policy only when it reflects your intent.

| Policy | Result |
| --- | --- |
| `ExactlyOne` | One and only one matching entry. |
| `First` | The first matching entry. |
| `Last` | The last matching entry. |
| `All` | Every matching entry. |

## Replace, Remove, or Move a Match

`ReplaceMatching` replaces the selected array entry or entries. `RemoveMatching` removes them. `MoveMatching` selects exactly one entry and moves it to `Start`, `End`, or before/after one `Find` anchor.

```json
{
  "Op": "ReplaceMatching",
  "Path": "/Rows",
  "Match": { "Id": "a" },
  "Value": { "Id": "a", "Enabled": true }
}
```

For `MoveMatching`, `Before` and `After` need a separate `Find` matcher. `Start` and `End` do not accept one. A missing, ambiguous, or self-referencing anchor fails the operation.

## Merge a Matching Object

`MergeMatching` finds object entries and deep-merges an object `Value` into each selected entry. Unrelated fields remain.

```json
{
  "Op": "MergeMatching",
  "Path": "/Rows",
  "Match": { "Id": "a" },
  "Value": { "Enabled": true }
}
```

## Upsert a Matching Object

`UpsertMatching` does the same merge if a match exists. If no entry matches, it inserts exactly one `Value` object instead. Its `Position` defaults to `End`; `Before` and `After` require `Find`.

```json
{
  "Op": "UpsertMatching",
  "Path": "/Rows",
  "Match": { "Id": "a" },
  "Position": "End",
  "Value": { "Id": "a", "Enabled": true }
}
```

All matching operations require an existing array at `Path`. Begin with `ExactlyOne`, then loosen the policy only after inspecting real target data.
