---
title: "Use the Hytale Asset Editor"
order: 4
published: true
draft: false
---

# Use the Hytale Asset Editor

Parent: [Start Here](/mod/patchwork/start-here) | [Home](/mod/patchwork/home)

Patchwork registers `Server/Patchwork/Patches/**/*.json` as a native asset type. In a current Patchwork installation, the Hytale Asset Editor can create, edit, validate, and save the same portable JSON files that Patchwork uses at runtime.

## Create a Definition

1. In your mod's asset pack, create a JSON asset below `Server/Patchwork/Patches/`.
2. Open it in the Asset Editor.
3. Fill in a target and add one operation.
4. Use the labeled choices for operation type, array position, match policy, and conflict policy.
5. Save, then use `/patchwork status` or the server log to check the result.

> [Screenshot Placeholder: The Patchwork definition editor showing Target, Operations, and an operation-type dropdown.]

The editor provides field help and guided structure, but it cannot know whether the target asset has the field you intend to change. Inspect the target asset first and begin with one small operation.

## If the Patchwork Type Does Not Appear

After installing the standalone runtime, restart Hytale so its asset registration can load. Confirm that your file is under the neutral Patchwork location exactly:

```text
Server/Patchwork/Patches/your-definition.json
```

The editor is a convenience, not a different format. You may also write the JSON by hand, keep it in version control, and reopen it in the editor later without adding editor-only metadata.

## Useful Editor Habits

- Give the definition and important operations memorable IDs.
- Start with a single exact target, not a glob.
- Read nonblank field help before filling an advanced field.
- Let the editor validate structure, then test the real patch on a server.
- Keep compatibility-only `FormatVersion` and `RequireFormat` fields out of new definitions; new neutral definitions do not need them.

For the meaning of each part of the file, continue to [Patch Anatomy](/mod/patchwork/patch-anatomy).
