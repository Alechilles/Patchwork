---
title: "Conditions: Apply Only When Needed"
order: 7
published: true
draft: false
---

# Conditions: Apply Only When Needed

Parent: [Authoring Guides](/mod/patchwork/authoring-guides) | [Home](/mod/patchwork/home)

`When` makes a patch conditional. Use conditions for optional integrations and compatibility, not as a substitute for testing the target asset.

## Common Conditions

Apply only when another mod is installed:

```json
{ "When": { "ModInstalled": "ExampleAuthor:ExampleMod" } }
```

Apply only within a version range:

```json
{
  "When": {
    "ModVersion": {
      "Mod": "ExampleAuthor:ExampleMod",
      "AtLeast": "2.1",
      "Below": "3.0"
    }
  }
}
```

Check whether an asset exists or is missing:

```json
{ "When": { "AssetExists": "Server/Item/Items/Optional.json" } }
```

```json
{ "When": { "AssetMissing": { "Asset": "Server/Item/Items/Old.json" } } }
```

`TargetExists: true` checks the current target. `TargetProvidedBy` checks that the resolved target came from one exact source-pack ID.

## Check JSON Values

`JsonPathExists` and `JsonPathEquals` inspect JSON through a pointer. Without `Source`, the current target is used.

```json
{
  "When": {
    "JsonPathEquals": {
      "Path": "/Interaction/Enabled",
      "Value": true
    }
  }
}
```

You can inspect another exact asset by using a `Source` with `Type: "Asset"`, or JSON in another installed Java mod's registered data directory using `Type: "ModData"`.

```json
{
  "When": {
    "JsonPathEquals": {
      "Source": {
        "Type": "ModData",
        "Mod": "ExampleAuthor:ExampleMod",
        "Path": "settings/config.json"
      },
      "Path": "/features/myFeature",
      "Value": true
    }
  }
}
```

Mod-data paths are relative to the registered data directory; they cannot use absolute paths or escape with `.` or `..`. Treat this data as non-secret: a patch-bearing asset pack has read-only access for conditions, and Patchwork never copies the values into generated output.

## Combine Conditions

Use `All` when every condition must be true, `Any` when one is enough, and `Not` to reverse one condition.

```json
{
  "When": {
    "All": [
      { "ModInstalled": "ExampleAuthor:ExampleMod" },
      { "Not": { "AssetExists": "Server/Item/Items/Disabled.json" } }
    ]
  }
}
```

Each condition object has exactly one condition key; `$Comment` may appear beside it. Invalid syntax, unreadable input, or a malformed JSON document fails the affected target rather than silently treating it as false.
