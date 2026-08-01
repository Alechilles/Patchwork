# Patch Format

Patchwork discovers JSON definitions from asset packs. Use the neutral root for new work:

```text
Server/Patchwork/Patches/**/*.json
```

When `Alechilles:Alec's Tamework!` is installed, Patchwork also reads the compatibility root:

```text
Server/Tamework/Patches/**/*.json
```

Roots are scanned from legacy to neutral. Within a source pack, an enabled neutral definition shadows an enabled legacy definition with the same source-pack ID, patch ID, and target. Disabled definitions are reported as skipped; they neither collide with another definition nor shadow legacy input.

A duplicate enabled key inside the same root rejects the entire later definition file, including any unrelated targets expanded from that file. The generated pack is never scanned as an input.

## Definition fields

```json
{
  "Id": "Example_Wider_Container",
  "Target": "Server/Item/Items/Example_Container.json",
  "Priority": 10,
  "Enabled": true,
  "When": {
    "ModInstalled": "Example:Containers"
  },
  "Operations": [
    {
      "Id": "set-capacity",
      "Op": "Replace",
      "Path": "/Container/Capacity",
      "Value": 48
    }
  ]
}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `Id` | No | Stable patch ID. Defaults to the source pack and definition path. |
| `Target` | One target form | One normalized asset path. |
| `Targets` | One target form | Non-empty array of unique normalized asset paths. Cannot be combined with `Target`. |
| `Priority` | No | Integer ordering value; default `0`. Lower values apply first. |
| `Enabled` | No | Default `true`. Disabled definitions are reported as skipped. |
| `When` | No | One condition object. Missing means always eligible. |
| `Operations` | Yes | Array of operations, applied in order. |

Definitions for the same target are ordered by `Priority`, then patch `Id`, then contributing pack load order. Each target is generated independently: a failed target does not prevent unrelated valid targets from being generated.

Asset paths use forward-slash form, such as `Server/Item/Items/Foo.json`. Absolute paths, drive-prefixed paths, empty segments, `.` and `..` are rejected.

## Operation fields

Every operation requires `Op`. `Id` defaults to `<patch-id>#<operation-index>`, and `Required` defaults to `true`.

When a required operation fails, that target fails. With `"Required": false`, the operation is reported as skipped and later operations continue.

Paths use JSON Pointer syntax. `/A/B/0` addresses an array entry; `~1` represents `/` and `~0` represents `~` inside a token.

### Add

Adds or overwrites an object property, or inserts at an array index. `-` appends to an array.

```json
{ "Op": "Add", "Path": "/Tags/-", "Value": "Example" }
```

The parent must already exist.

### Merge

Recursively merges object properties. Both the target and `Value` must be objects.

```json
{
  "Op": "Merge",
  "Path": "/Interaction",
  "Value": {
    "Cooldown": 20,
    "Enabled": true
  }
}
```

### Replace

Replaces an existing object property or array entry.

```json
{ "Op": "Replace", "Path": "/Container/Capacity", "Value": 48 }
```

### Remove

Removes an existing property or array entry.

```json
{ "Op": "Remove", "Path": "/DeprecatedField" }
```

### Insert

Inserts `Value` into an existing array.

```json
{
  "Op": "Insert",
  "Path": "/Interactions",
  "Position": "After",
  "Find": { "Id": "Open" },
  "Existing": { "Id": "Inspect" },
  "Value": { "Id": "Inspect", "Type": "Inspect" }
}
```

`Position` is case-insensitive and defaults to `End`:

- `Start`: first entry.
- `End`: last entry.
- `Before`: before the first entry matching `Find`.
- `After`: after the first entry matching `Find`.

`Existing` is optional. If it matches an entry already in the array, insertion is skipped. Matchers recursively match the fields they declare. Inside a matcher, `"$Contains": { ... }` matches when an array contains an object satisfying the nested matcher.

`Before` and `After` require `Find` and fail if no anchor matches.

### Host macro

An embedding plugin can contribute macro IDs that expand into ordinary operations before application:

```json
{
  "Op": "Macro",
  "Macro": "ExampleMacro",
  "Options": {
    "Mode": "Fast"
  }
}
```

Macro names and option schemas belong to the contributing host. A missing provider fails the affected target; Patchwork never publishes a partially modified target.

## Conditions

A condition object defines exactly one condition key. `$Comment` may accompany that key.

### Installed mods and versions

```json
{ "ModInstalled": "Example:Mod" }
```

```json
{
  "ModVersion": {
    "Mod": "Example:Mod",
    "AtLeast": "2.1",
    "Below": "3.0"
  }
}
```

```json
{ "ServerVersion": { "AtLeast": "0.5", "AtMost": "0.5.9" } }
```

`GameVersion` is accepted as an alias for `ServerVersion`. Matchers may use `Equals`, `AtLeast`, `AtMost`, `Above`, and `Below`. Versions are exact dotted numeric segments, not semantic-version ranges.

### Asset and target presence

```json
{ "AssetExists": "Server/Item/Items/Optional_Item.json" }
```

```json
{ "AssetMissing": { "Asset": "Server/Item/Items/Old_Item.json" } }
```

```json
{ "TargetExists": true }
```

`TargetExists` refers to the current definition's target.

### JSON path checks

`JsonPathExists` and `JsonPathEquals` use JSON Pointer syntax. The empty pointer addresses the entire document.

The default source is the current target:

```json
{
  "JsonPathEquals": {
    "Path": "/Interaction/Enabled",
    "Value": true
  }
}
```

Explicit target source:

```json
{
  "JsonPathExists": {
    "Source": { "Type": "Target" },
    "Path": "/Container"
  }
}
```

Another asset:

```json
{
  "JsonPathEquals": {
    "Source": {
      "Type": "Asset",
      "Path": "Server/Item/Items/Other.json"
    },
    "Path": "/Enabled",
    "Equals": true
  }
}
```

The legacy `"Asset": "Server/...json"` field remains readable when `Source` is absent. Do not combine `Asset` and `Source`.

Registered Java mod data:

```json
{
  "JsonPathEquals": {
    "Source": {
      "Type": "ModData",
      "Mod": "Example:Mod",
      "Path": "settings/config.json"
    },
    "Path": "/features/myFeature",
    "Value": true
  }
}
```

Mod IDs match exactly. Paths are relative, remain below the selected plugin data directory, cannot contain `.` or `..`, and are read without following links. Documents are limited to 4 MiB. Each distinct source document is resolved and parsed once per generation pass, so every condition in that pass sees one snapshot.

Installing a patch-bearing asset pack grants it read-only conditional access to non-secret JSON under registered Java mod data directories. Patchwork does not substitute source values into output and does not include actual or expected values in status output. Mods should not store secrets in JSON intended for cross-mod conditions.

`TameworkSetting` is retired. Migrate it to `JsonPathEquals` with a `ModData` source as shown above.

### Boolean composition

```json
{
  "All": [
    { "ModInstalled": "Example:Mod" },
    {
      "Any": [
        { "AssetExists": "Server/Item/Items/A.json" },
        { "AssetExists": "Server/Item/Items/B.json" }
      ]
    },
    { "Not": { "AssetExists": "Server/Item/Items/Disabled.json" } }
  ]
}
```

`All` and `Any` require non-empty arrays. `Not` wraps one condition. Unsafe paths, unreadable or malformed documents, invalid condition syntax, and other evaluation errors fail the affected target rather than silently acting as false.
