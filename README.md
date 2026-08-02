![image](https://media.forgecdn.net/attachments/description/1634146/description_b14a69bf-f6b3-4938-9b1b-7abfc8abc1cd.png) _Small patches. Better compatibility._

Every mod adds its own piece to Hytale. Trouble starts when two pieces need to change the same asset.

Patchwork lets mod authors stitch focused changes into existing JSON assets without shipping complete replacement files. Add a field, merge configuration, insert new behavior, remove something obsolete, or conditionally adapt to another installed mod—all while leaving the original asset untouched.

Your patches ship alongside your mod. Patchwork gathers the applicable pieces, layers them over the currently loaded assets, and publishes the finished result through a generated asset pack.

The original files remain exactly where they were.

***

## Patch only what you need

![image](https://media.forgecdn.net/attachments/description/1634146/description_bb2d8cdf-e3d8-4515-ba49-59be771c9f5f.png)

Instead of copying an entire asset to change one small section, describe the change itself:

```json
{
  "Id": "MyMod_EnableFollowing",
  "Target": "Server/NPC/Roles/Creature/Mammal/Cow.json",
  "When": {
    "ModInstalled": "ExampleAuthor:ExampleMod"
  },
  "Operations": [
    {
      "Id": "EnableFollowing",
      "Op": "Merge",
      "Path": "/Parameters",
      "Value": {
        "CanFollow": {
          "Value": true
        }
      }
    }
  ]
}
```

This patch changes the cow only when the specified mod is installed. Patchwork resolves the current version of the target asset, applies the requested operation, and generates the finished asset at runtime.

No bundled override. No manually maintained duplicate. No change when the integration is not needed.

***

## Designed for mod integrations

Patchwork is built for mods that need to cooperate without becoming tightly coupled.

Use it to:

*   Add optional behavior when another mod is installed.
*   Extend an asset without replacing everything around it.
*   Apply one change across several related assets.
*   Create compatibility layers that activate only when relevant.
*   Adapt to particular mod or game versions.
*   Check whether assets, fields, or configuration values exist before applying a change.
*   Read supported JSON configuration from another installed mod’s registered data directory.
*   Keep your base assets valid even when an optional dependency is absent.

A patch can stand alone, use several conditions together, or target multiple assets at once. Conditions can be combined with `All`, `Any`, and `Not`, allowing integrations to be as narrow or flexible as they need to be.

***

## A small language for precise changes

Patchwork supports five core JSON operations:

*   **Add** — add a new field or array entry.
*   **Merge** — blend an object into an existing section.
*   **Replace** — change a value that is already present.
*   **Remove** — remove an existing field or entry.
*   **Insert** — place an entry before, after, at the start of, or at the end of an array.

![image](https://media.forgecdn.net/attachments/description/1634146/description_8e6afbd4-849d-4643-9583-8a0411683670.png)

Insert operations can locate stable anchors instead of relying on fragile array indexes. They can also detect whether their content already exists, making patches safe to regenerate without duplicating entries.

If a required operation cannot be completed, Patchwork rejects that target instead of publishing a half-applied asset. Clear diagnostics identify the patch, operation, target, and reason.

***

## Conditional by design

Compatibility patches should appear only when they make sense.

Patchwork can condition changes on:

*   Installed mods
*   Mod, game, or server versions
*   Existing or missing assets
*   Whether the target asset exists
*   JSON fields and values within an asset
*   JSON configuration stored in another installed Java mod’s registered data directory
*   Nested combinations of conditions

This allows a mod to ship its integrations directly, rather than asking server owners to download and arrange a collection of separate compatibility packs.

Patchwork’s access to other mod data is read-only and confined to registered mod data directories. It cannot use absolute paths or escape into unrelated parts of the filesystem.

***

## One Patchwork, however it arrives

Patchwork is available as both a standalone mod and an embeddable Java runtime.

Mod authors can declare Patchwork as a dependency or embed it directly so their patches continue to work without requiring server owners to install another jar manually.

![image](https://media.forgecdn.net/attachments/description/1634146/description_ea7cbca7-fd56-4022-9852-92fd063e4678.png)

If several mods bring their own copy, Patchwork elects exactly one active runtime:

1.  The newest compatible version wins.
2.  If versions are equal, the standalone installation is preferred.
3.  Every other copy remains passive.

Only the elected runtime scans patches, generates assets, registers commands, and performs background work. Embedded copies can still contribute integrations without creating duplicate patch systems.

***

## Built for coexistence

Patchwork never edits the source files belonging to Hytale or another mod. It generates patched copies in its own runtime asset pack, preserving the original materials underneath.

Patches are applied in a stable, deterministic order. Multiple mods can contribute changes to the same target, and failed targets are kept out of the generated output rather than being published in a partially modified state.

Some asset types can update live, while others may require a restart. Patchwork reports that distinction honestly through its status and reload tools.

***

## Tamework compatibility

Patchwork began as the universal asset patcher inside Alec’s Tamework.

The standalone release preserves the existing patch format and continues to recognize legacy patches under `Server/Tamework/Patches` when Tamework is installed. New integrations should use the neutral location:

```text
Server/Patchwork/Patches
```

Tamework can embed Patchwork and contribute its own macros and reload behavior, while the core patching system remains useful to every mod—not only Tamework extensions.

***

## For server owners

Most of Patchwork is intended to work quietly.

Install the standalone jar when a mod lists Patchwork as a required dependency. If a mod already embeds a compatible version, no additional installation is necessary unless that mod’s documentation says otherwise.

Patchwork automatically prevents duplicate runtimes from doing duplicate work.

Administrative tools are available under:

```text
/patchwork
```

These tools provide patch status, diagnostics, explicit regeneration, and self-testing when troubleshooting an integration. They require the `patchwork.admin` permission and default to the `hytale:Admin` group.

***

## Authoring and technical details

The standalone plugin registers `Server/Patchwork/Patches/**/*.json` as a native Hytale asset type. Hytale’s Asset Editor can discover, create, structurally edit, validate, and save the same portable definitions consumed by Patchwork.

Patchwork 1.0.0 supports these installation modes:

*   Install `patchwork-standalone-1.0.0.jar` as a Hytale server mod.
*   Embed `com.alechilles:patchwork-runtime:1.0.0` in another Java plugin.

Read the [patch format](docs/Patch-Format.md), [operations guide](docs/Operations.md), [embedding contract](docs/Embedding.md), and [runtime election notes](docs/Runtime-Election.md) for the complete technical details.

***

## Make mods fit together

A good compatibility layer should feel like it was always part of the design.

Patchwork gives mod authors a shared way to layer focused, conditional changes over Hytale assets—without copying whole files, forcing hard dependencies, or unraveling one another’s work.

Bring your own piece. Patchwork will help stitch it in.

***

**Source:** this repository
**For mod authors:** Patch definitions belong under `Server/Patchwork/Patches/**/*.json`

Patchwork is source-available under the [Patchwork Source Available License 1.0](LICENSE.txt).
