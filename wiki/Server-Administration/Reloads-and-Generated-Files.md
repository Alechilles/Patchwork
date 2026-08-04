---
title: "Reloads and Generated Files"
order: 3
published: true
draft: false
---

# Reloads and Generated Files

Parent: [Server Administration](/mod/patchwork/server-administration) | [Home](/mod/patchwork/home)

Patchwork creates a separate asset pack rather than changing game or mod source files. Its default generated-pack root is:

```text
<server-or-save-root>/mods/Alechilles_Patchwork/GeneratedPatches
```

The generated pack ID is `Alechilles:Patchwork_GeneratedPatches`. It contains Hytale's `manifest.json`, Patchwork's `patchwork-manifest.json`, and the generated target files. The Patchwork manifest records target paths, sizes, and integrity hashes; it does not contain source content or condition data.

## Do Not Edit the Generated Pack

Patchwork owns this directory. Manual edits will be replaced by the next generation and can make diagnosis harder. Never put patch definitions inside this generated pack: Patchwork excludes it from inputs to prevent feedback loops.

## When Generation Happens

Patchwork generates at server startup, when an administrator runs `/patchwork reload`, and after relevant changes in a directory asset pack. Automatic changes wait about one second so a burst of edits becomes one pass; a further relevant change during generation queues one more pass.

Definitions, relevant target assets, exact cross-asset sources, and glob prefixes contribute to this decision. Changes in archive packs and unregistered mod-data directories need the manual command or a restart.

## Generation Is Not Always a Live Reload

Patchwork calls a target `hot-reloaded` only after Hytale reports the expected generated provider, exact path, and expected bytes. It does not claim success merely because a file was written.

| Result | What you should do |
| --- | --- |
| `generated` | The target is in the current generated inventory. Check the reload result too. |
| `hot-reloaded` | Hytale confirmed the expected Patchwork target is live. |
| `adapter-reloaded` | An embedding host confirmed its supported live-reload route. |
| `restart-required` | The generated state is on disk; restart normally before expecting it in game. |
| `removed` | A previously generated target was removed and that removal was confirmed. |
| `stale` | The live state could not be confirmed; investigate before claiming success. |
| `rollback-failed` | Live state is uncertain. Stop repeated reload attempts and restart from known-good assets. |
| `skipped` or `failed` | Read the related status and first server-log diagnostic. |

## Last-Known-Good Evidence

Startup publication is staged and verified. When replacing a generated pack, Patchwork keeps the prior generated root under `Diagnostics/GeneratedPatches-prior-*` as evidence. A prior directory alone is normal; it is not automatically a failure. Preserve diagnostic folders while investigating. Remove an old successful prior copy only in an offline maintenance window after the active pack is verified.
