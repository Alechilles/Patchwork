---
title: "Copy Data from Another Asset"
order: 6
published: true
draft: false
---

# Copy Data from Another Asset

Parent: [Authoring Guides](/mod/patchwork/authoring-guides) | [Home](/mod/patchwork/home)

Cross-asset operations let a patch read object data from another exact asset during generation. They are useful when an integration needs to reuse compatible configuration without copying it into several patch files.

The source is read from the one immutable asset snapshot captured for that generation pass. Patchwork never changes the source asset, and `Source` never accepts `glob:`.

## Overlay an Entire Asset

`OverlayFromAsset` deep-merges the source asset object into the current target. Source values win when both objects have the same leaf; target fields with no source counterpart remain.

```json
{
  "Op": "OverlayFromAsset",
  "Source": "Server/NPC/Roles/Base.json"
}
```

Later operations can still replace or merge an imported value. Patchwork rejects a self-overlay.

## Merge One Source Object

`MergeObjectFromAsset` takes an object from the source and merges it into an existing object in the target. `SourcePath` is optional and means the source root when omitted.

```json
{
  "Op": "MergeObjectFromAsset",
  "Source": "Server/NPC/Roles/Base.json",
  "SourcePath": "/SharedSettings",
  "Path": "/Parameters"
}
```

`Path` must point to an existing target object. The selected source value must also be an object. Source fields win, while unrelated fields already in the target object stay in place.

## Make an Integration Optional

If the source asset is legitimately present only with another mod setup, set `Required` to `false` so this one operation is reported as skipped instead of rejecting the target:

```json
{
  "Op": "MergeObjectFromAsset",
  "Source": "Server/Example/OptionalSource.json",
  "Path": "/Parameters",
  "Required": false
}
```

Missing sources or source paths, and non-object source or destination values, are applicability failures. Invalid source path syntax remains a definition error and must be corrected.
