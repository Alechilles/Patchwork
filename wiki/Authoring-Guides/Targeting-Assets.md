---
title: "Choose the Assets to Change"
order: 3
published: true
draft: false
---

# Choose the Assets to Change

Parent: [Authoring Guides](/mod/patchwork/authoring-guides) | [Home](/mod/patchwork/home)

`Target` tells Patchwork which current asset to copy and change. It is an asset path, not a Windows path or a path to a file on your disk.

## Use Exact Paths First

For one asset, write its normalized path with forward slashes:

```json
{
  "Target": "Server/NPC/Roles/Creature/Mammal/Cow.json",
  "Operations": [
    { "Op": "Replace", "Path": "/Enabled", "Value": true }
  ]
}
```

Exact paths are the easiest to review and the least likely to modify more assets than intended. Patchwork rejects absolute paths, drive letters, empty path segments, `.` and `..` segments.

## Target Several Assets

Use `Targets` for a small, deliberate list. Do not include `Target` in the same definition.

```json
{
  "Targets": [
    "Server/Item/Items/A.json",
    "Server/Item/Items/B.json"
  ],
  "Operations": [
    { "Op": "Add", "Path": "/Tags/-", "Value": "MyMod" }
  ]
}
```

Each target is generated independently. A failure for `A.json` does not prevent a valid patch for `B.json`.

## Use a Glob Only When You Mean a Group

To select a changing or broad group, begin the selector with `glob:`:

```json
{
  "Target": "glob:Server/NPC/**/*.json",
  "Operations": [
    { "Op": "Replace", "Path": "/Enabled", "Value": true }
  ]
}
```

| Token | Meaning |
| --- | --- |
| `*` | Any number of characters within one folder or filename segment. |
| `**` | Zero or more whole path segments. |
| `?` | Exactly one character within a path segment. |

Raw `*` and `?` are not patterns. If you use one, the value must start with `glob:`. Patchwork does not support regular expressions.

Patchwork expands a glob from one fixed snapshot of the currently loaded assets, removes duplicate results, orders them predictably, and never reads its own generated pack as input. A selector that matches nothing produces a warning rather than creating an invented asset.

## A Practical Targeting Checklist

1. Open the asset you intend to change and copy its asset path.
2. Start with one exact target.
3. Test the result and inspect `/patchwork status`.
4. Only replace it with `Targets` or `glob:` when the same operation is appropriate for every selected asset.
5. Add conditions if the target depends on another mod or version.

Use [Conditions: Apply Only When Needed](/mod/patchwork/conditions-apply-only-when-needed) for optional integrations.
