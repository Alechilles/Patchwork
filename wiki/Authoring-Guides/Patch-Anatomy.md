---
title: "Patch Anatomy"
order: 2
published: true
draft: false
---

# Patch Anatomy

Parent: [Authoring Guides](/mod/patchwork/authoring-guides) | [Home](/mod/patchwork/home)

A patch definition is one JSON file below `Server/Patchwork/Patches/`. It names an asset (or assets), optionally says when it should apply, and lists the changes in order.

```json
{
  "$Comment": "Adds an optional integration.",
  "Id": "MyMod_ExampleIntegration",
  "Target": "Server/Item/Items/Example.json",
  "Priority": 10,
  "Enabled": true,
  "When": { "ModInstalled": "ExampleAuthor:ExampleMod" },
  "Operations": [
    {
      "Id": "add-tag",
      "Op": "Add",
      "Path": "/Tags/-",
      "Value": "MyMod"
    }
  ]
}
```

## Root Fields

| Field | What it is for |
| --- | --- |
| `$Comment` | A note for people. Patchwork ignores it. |
| `Id` | A stable, memorable patch name. It is strongly recommended. |
| `Target` | One exact asset path or one `glob:` selector. |
| `Targets` | A list of asset paths/selectors. Use this instead of `Target` when needed; never use both. |
| `Priority` | A whole number. Lower values apply first; the default is `0`. |
| `Enabled` | `true` by default. Set `false` to leave the file in place but skip it. |
| `ConflictPolicy` | How this new neutral definition handles an overlap it introduces. See [Optional Changes and Conflict Reports](/mod/patchwork/optional-changes-and-conflict-reports). |
| `When` | An optional condition. Without it, the patch is eligible whenever its target exists. |
| `Operations` | The non-empty list of requested changes. They run from top to bottom. |

## Operation Fields You Will See Often

Every operation has `Op`, which chooses the kind of change. An operation can have its own `Id`; if omitted, Patchwork gives it a predictable ID based on the patch and its position.

`Required` defaults to `true`. If a required operation cannot apply, Patchwork rejects only that target. Use `"Required": false` only when it is genuinely okay for that one operation to be absent, such as an integration with an optional asset.

Most operations use:

- `Path` — a [JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901), such as `/Parameters/Enabled`;
- `Value` — the JSON value to add, merge, replace, or insert; and sometimes
- `Match`, `Find`, `Position`, or `Source` — described in the specialized guides.

## New Definitions Use the Neutral Format

For new files in `Server/Patchwork/Patches/`, omit `FormatVersion` and do not add a `RequireFormat` operation. This marker-free neutral format is what the Asset Editor creates and what current Patchwork releases support. Unknown structural fields or operation names are rejected clearly instead of being treated as an optional no-op.

Older explicit format 1 and format 2 files remain supported for compatibility. See [Compatibility and Versions](/mod/patchwork/compatibility-and-versions) before changing one.

## Ordering Matters

Within a definition, operations run in the order written. Across definitions that touch the same target, Patchwork uses `Priority`, patch ID, contributing pack order, and pack ID to get a repeatable result. Do not depend on filenames as an ordering mechanism; use `Priority` when you intentionally need an earlier or later patch.
