---
title: "Core Operations"
order: 4
published: true
draft: false
---

# Core Operations

Parent: [Authoring Guides](/mod/patchwork/authoring-guides) | [Home](/mod/patchwork/home)

An operation is one requested change. Read the target JSON before choosing one: the right operation depends on whether a field already exists and whether you are changing an object or an array.

## JSON Pointers in Plain Language

`Path` starts with `/` and walks through JSON property names or array positions. For example, `/Container/Capacity` means the `Capacity` property inside `Container`; `/Tags/0` means the first array entry.

If a property name itself contains `/`, write it as `~1`. If it contains `~`, write it as `~0`. Array indexes are zero-based: the first entry is `0`. For an array append with `Add`, use `-` as the final path token, such as `/Tags/-`.

## Add

`Add` adds or overwrites an object property, or inserts into an existing array.

```json
{ "Op": "Add", "Path": "/Tags/-", "Value": "MyMod" }
```

The parent location must already exist. For an object property, `Add` is useful when a new field is acceptable; do not use it when you want a missing field to be an error.

## Replace

`Replace` changes a property or array entry that already exists.

```json
{ "Op": "Replace", "Path": "/Container/Capacity", "Value": 48 }
```

Use it when your patch should fail loudly if the upstream asset no longer has the expected field.

## Merge

`Merge` combines an object into an existing object, keeping unrelated properties.

```json
{
  "Op": "Merge",
  "Path": "/Interaction",
  "Value": { "Cooldown": 20, "Enabled": true }
}
```

Both the selected target and `Value` must be JSON objects. It is not an array operation.

## Remove

`Remove` deletes an existing object property or array entry.

```json
{ "Op": "Remove", "Path": "/DeprecatedField" }
```

Use an optional operation only if the field may legitimately be absent across supported setups:

```json
{ "Op": "Remove", "Path": "/OldIntegration", "Required": false }
```

## Insert

`Insert` adds `Value` to an existing array. It defaults to the end, or you can place it at the start, before, or after an anchor.

```json
{
  "Op": "Insert",
  "Path": "/Interactions",
  "Position": "After",
  "Find": { "Id": "Open" },
  "Value": { "Id": "Inspect", "Type": "Inspect" }
}
```

`Before` and `After` require `Find`. Use `Existing` to skip insertion when equivalent content is already present:

```json
{
  "Op": "Insert",
  "Path": "/Interactions",
  "Existing": { "Id": "Inspect" },
  "Value": { "Id": "Inspect", "Type": "Inspect" }
}
```

For safe edits to an array entry selected by its contents, continue to [Array and Matching Operations](/mod/patchwork/array-and-matching-operations).
