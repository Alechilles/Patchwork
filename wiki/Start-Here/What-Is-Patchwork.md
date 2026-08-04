---
title: "What Is Patchwork?"
order: 2
published: true
draft: false
---

# What Is Patchwork?

Parent: [Start Here](/mod/patchwork/start-here) | [Home](/mod/patchwork/home)

Patchwork is a compatibility tool for Hytale assets. An asset is a JSON file that tells Hytale how part of the game should work: an item, NPC role, interaction, configuration entry, and more.

Normally, changing one property in an asset means distributing a replacement copy of the whole file. If another mod replaces that same file, one mod can overwrite the other. Patchwork avoids that problem by keeping a separate list of focused changes.

## What Patchwork Does

For each patch, Patchwork:

1. Finds the current version of the target asset.
2. Checks whether the patch is enabled and its conditions are true.
3. Applies the listed changes in a stable order.
4. Publishes only the finished copy in Patchwork's generated asset pack.

The original game and mod files are never edited. If a patch fails, Patchwork rejects that target instead of publishing a partly changed version; unrelated targets can still be generated.

## When to Use It

Patchwork is a strong fit when your mod needs to:

- add or alter a small part of an existing asset;
- make an optional integration when another mod is installed;
- add an entry to an asset array without copying the rest of the file;
- apply the same focused change to several assets; or
- avoid maintaining a duplicate asset as Hytale or another mod updates it.

## What It Is Not

Patchwork does not write changes back into source files, and it does not make every Hytale asset reload live. A generated change may require a normal server restart before Hytale uses it. [Reloads and Generated Files](/mod/patchwork/reloads-and-generated-files) explains how to tell the difference.

## A Tiny Example

This definition changes an existing value in one asset:

```json
{
  "Id": "MyMod_WiderContainer",
  "Target": "Server/Item/Items/MyContainer.json",
  "Operations": [
    {
      "Op": "Replace",
      "Path": "/Container/Capacity",
      "Value": 48
    }
  ]
}
```

The definition belongs in your mod's asset pack under `Server/Patchwork/Patches/`. Read [Patch Anatomy](/mod/patchwork/patch-anatomy) before adapting the example.
