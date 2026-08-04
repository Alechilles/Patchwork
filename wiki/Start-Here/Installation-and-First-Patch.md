---
title: "Install Patchwork and Make Your First Patch"
order: 3
published: true
draft: false
---

# Install Patchwork and Make Your First Patch

Parent: [Start Here](/mod/patchwork/start-here) | [Home](/mod/patchwork/home)

This guide gets a mod author from an installed Patchwork jar to one small, safe patch. For a server owner who is only installing mods, see [Server Owner Guide](/mod/patchwork/server-owner-guide).

## Before You Start

You need:

- a Hytale server or modding workspace;
- the Patchwork standalone jar, unless the mod that supplies patches already embeds a compatible Patchwork runtime; and
- a mod asset pack where you can add JSON files.

Install the standalone jar as a server mod in the normal Hytale mods location. Restart the server after installing or updating it. Patchwork only needs one active runtime, so an embedded copy and a standalone copy will not both run the patch engine. See [Compatibility and Versions](/mod/patchwork/compatibility-and-versions) for the election rules.

## Create the Patch File

Inside the asset pack that owns your change, create this folder structure:

```text
Server/
  Patchwork/
    Patches/
      my-first-patch.json
```

> [Screenshot Placeholder: An Asset Editor project tree with Server/Patchwork/Patches/my-first-patch.json selected.]

Put a definition like this in the file. Replace the target path, pointer, and value with a small change that makes sense for your asset.

```json
{
  "Id": "MyMod_EnableExample",
  "Target": "Server/Example/Example.json",
  "Operations": [
    {
      "Id": "enable-example",
      "Op": "Replace",
      "Path": "/Enabled",
      "Value": true
    }
  ]
}
```

`Id` is a helpful, stable name. `Target` is the exact path of the asset you want to change. `Operations` is the list of changes; this example uses `Replace`, so `/Enabled` must already exist.

## Check the Result

1. Start or restart the server.
2. Give your administrator account the `patchwork.admin` permission if needed.
3. Run `/patchwork status` and look for your generated target or a diagnostic.
4. Test the changed asset in game.

If the server says the change is `restart-required`, restart normally before testing. Do not edit Patchwork's generated output to "fix" a problem; fix your patch definition instead.

## Common First-Patch Mistakes

- The file is outside `Server/Patchwork/Patches/`.
- `Target` is a filesystem path instead of an asset path. Use `Server/...`, never `C:\...`.
- `Replace` is used for a field that does not exist. Use [Core Operations](/mod/patchwork/core-operations) to choose the right operation.
- The JSON Pointer is wrong. A pointer begins with `/`, for example `/Container/Capacity`.
- Another change is expected to take effect live but is marked `restart-required`.

Continue with [Use the Hytale Asset Editor](/mod/patchwork/using-the-asset-editor) or [Patch Anatomy](/mod/patchwork/patch-anatomy).
