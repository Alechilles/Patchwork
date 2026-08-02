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

## Format versions

Definitions without `FormatVersion` use legacy format 1. Format 2 definitions set `"FormatVersion": 2` and are validated with a closed root and operation grammar. A format-2 definition must begin with exactly one compatibility sentinel:

```json
{ "Op": "RequireFormat", "Version": 2 }
```

The sentinel must be operation index zero, its `Version` must equal the root `FormatVersion`, and it cannot define `Required` or any other operation fields. A second or differently-cased `RequireFormat` is invalid. Format 1 remains supported explicitly; it does not acquire format-2 pointer or matcher semantics by inference.

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

Definitions for the same target are ordered by `Priority`, then patch `Id` by unsigned UTF-8 bytes, then contributing pack load order, then source-pack ID by unsigned UTF-8 bytes. Strings are compared exactly, without normalization or locale collation. The same total ordering is used when selecting a winning source pack. Each target is generated independently: a failed target does not prevent unrelated valid targets from being generated.

Asset paths use forward-slash form, such as `Server/Item/Items/Foo.json`. Absolute paths, drive-prefixed paths, empty segments, `.` and `..` are rejected.

## Operation fields

Every operation requires `Op`. `Id` defaults to `<patch-id>#<operation-index>`, and `Required` defaults to `true`.

When a required operation fails, that target fails. With `"Required": false`, the operation is reported as skipped and later operations continue.

Paths use JSON Pointer syntax. `/A/B/0` addresses an array entry; `~1` represents `/` and `~0` represents `~` inside a token.

### Format 2 pointers and matchers

Format 2 applies RFC 6901 pointer semantics before an operation can run:

- the empty pointer addresses the document root and `/` addresses an empty property name;
- only `~0` and `~1` escapes are valid;
- array indexes are `0` or non-zero digits without a leading zero, and must fit a non-negative 32-bit integer; and
- `-` is accepted only as the final `Add` token for array append.

Invalid pointer syntax is a structural error, so `Required: false` cannot hide it. Format 1 keeps its existing pointer compatibility behavior.

Format-2 matchers are non-empty objects. Ordinary keys recursively require the declared fields. Reserved operators must be the sole key of their matcher object:

```json
{ "$Equals": { "Id": "Exact", "Rank": 1 } }
{ "$Contains": { "$Equals": "needle" } }
```

`$Equals` performs strict recursive JSON equality (including object keys, array order, and numeric value equality). `$Contains` applies its nested matcher to at least one element of an array. A reserved operator combined with another key, an empty matcher, or malformed operator data is invalid before application.

### Matcher-based array operations

Format 2 adds three portable operations. Every operation requires an existing array at `Path` and a `Match` object. `MatchPolicy` defaults to `ExactlyOne` and is case-insensitive:

| Policy | Selection |
| --- | --- |
| `ExactlyOne` | exactly one match; zero or multiple matches fail |
| `First` | the lowest matching index; zero matches fail |
| `Last` | the highest matching index; zero matches fail |
| `All` | every matching index; zero matches fail |

`ReplaceMatching` additionally requires `Value` and deep-copies it into each selected entry. `RemoveMatching` removes selected entries from highest index to lowest. `MoveMatching` always selects exactly one entry, then moves it to `Start`, `End`, or immediately `Before`/`After` one `Find` anchor. `Before` and `After` require a `Find` matcher; `Start` and `End` forbid one. A self-anchor or an ambiguous/missing anchor fails the operation. Applicability failures follow `Required`: required failures reject the target, while optional failures are reported as skipped.

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

### Winning target provider

`TargetProvidedBy` compares an exact, case-sensitive source-pack ID with the provider that won target resolution:

```json
{ "TargetProvidedBy": "Example:Dragons" }
```

The condition is evaluated only after the target has resolved. A different or unavailable provider is `NOT_MATCHED`; Patchwork carries the already-resolved target snapshot into condition evaluation and does not perform a second lookup.

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
