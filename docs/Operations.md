# Patchwork Operations

## Commands and permission

The elected runtime registers one command tree:

```text
/patchwork status
/patchwork reload
/patchwork selftest
```

All commands require `patchwork.admin` and default to the `hytale:Admin` group. Passive copies do not register commands.

## Authoring contract

The portable format-2 authoring kit is shipped at `docs/authoring-kit/v2/` in the Patchwork source distribution:

- `patch-definition.schema.json` closes definition, operation, and condition descriptors while leaving JSON data containers opaque;
- `capabilities.json` is the current capability document (`supportedFormatVersions: [1, 2]`), including matcher operations and the exact `TargetProvidedBy` condition; and
- the runtime conformance corpus lives under `runtime/src/test/resources/authoring-kit/v2/`.

There is no built-in macro descriptor version. Macro option schemas remain host-provider data; a provider may publish descriptors below `Server/Patchwork/Authoring/Macros/**/*.json`.

## Generation triggers

Patchwork has exactly two generation triggers:

1. the early startup asset-load phase;
2. an authorized `/patchwork reload`.

Editing a definition, source asset, or referenced mod-data JSON file does not generate automatically. An asset observation can confirm a transaction already started by Patchwork, but it cannot start generation.

Every pass captures a fresh view of loaded asset packs, installed plugins, plugin versions, and registered mod-data roots. Condition documents are cached only for that pass.

## Generated pack

Pack ID:

```text
Alechilles:Patchwork_GeneratedPatches
```

Default root:

```text
<server-or-save-root>/mods/Alechilles_Patchwork/GeneratedPatches
```

The active root contains Hytale `manifest.json`, `patchwork-manifest.json`, and generated targets. The Patchwork manifest records each target path, byte length, and SHA-256 integrity hash; it does not contain source content or condition data.

Patchwork owns this directory. Do not edit generated files by hand or place patch definitions inside it.

## Startup publication

Startup generation is staged under a unique sibling directory and verified before activation. If a previous generated pack exists, Patchwork preserves it as last-known-good until the replacement is proven active.

Diagnostic names include unique staging, prior, and failed-new directories. A successful replacement normally retains the previous generated root under `Diagnostics/GeneratedPatches-prior-*` as last-known-good evidence, so a prior directory alone does not indicate failure. A failed-new directory or a prior directory referenced by an unresolved publication result is failure evidence. Patchwork 1.1.0 has no automatic evidence-pruning command; preserve evidence while diagnosing, and remove an old successful prior copy only during an offline maintenance window after the active pack has been verified.

A target can be rejected without blocking unrelated valid targets. Patchwork reports scan failures and rejected targets while publishing only a verified generated pack.

## Reload transactions

`/patchwork reload` admits only one administration operation at a time. It rescans current generated output, takes a fresh input snapshot, creates a new plan, writes the exact generated manifest, and applies target-local transactions.

Patchwork never invokes a generic Hytale asset reload. Standalone Patchwork 1.1.0 does not wire a built-in live target route, so its changed targets are conservatively reported as restart-required. An embedding plugin can contribute an explicit host adapter; targets accepted and confirmed by that adapter can be reported as adapter-reloaded.

Target outcomes:

| Outcome | Meaning |
| --- | --- |
| `generated` | Target is present in the current generated inventory. |
| `removed` | Previously generated target was intentionally removed and confirmed. |
| `hot-reloaded` | Reserved status category for a composed built-in route. Stock standalone 1.1.0 does not produce this outcome. |
| `adapter-reloaded` | A host contribution reloaded and confirmed the target. |
| `restart-required` | The desired generated state—replacement bytes or removal—is committed on disk, but it cannot be safely applied live. Restart the server to consume it. |
| `stale` | The live target could not be confirmed at the expected state. |
| `rollback-failed` | Apply failed and Patchwork could not prove the prior target was restored. Treat live state as uncertain. |
| `skipped` | Definition or condition was intentionally not applied. |
| `failed` | Scan, parse, condition, generation, apply, or confirmation failed for the target. |

Patchwork preserves durable rollback evidence when live state cannot be reconciled. Do not claim success from file presence alone; use status and the server log.

## Status

`/patchwork status` shows bounded, sanitized information:

- active/passive runtime candidates and election reasons;
- host contributions;
- neutral and legacy root eligibility;
- last generation epoch, inventory count, skips, and failures;
- last reload epoch, manifest state, integrity state, and per-target categories.

If current generated inventory cannot be safely scanned, status explicitly reports it as unknown. Condition source values, expected values, document contents, rollback bytes, and registration tokens are not exposed.

## Self-test

`/patchwork selftest` creates one UUID-named run below Patchwork's self-test root. It writes isolated source, generated, and mod-data fixtures, runs the real generation engine, verifies expected JSON pointers for every built-in patch operation (`Add`, `Merge`, `Replace`, `Remove`, `Insert`, `ReplaceMatching`, `RemoveMatching`, and `MoveMatching`), and cleans only that exact run. Host macros are not included because their behavior is supplied by an embedding plugin.

The command reports the pass/fail result of each completed fixture in-game, followed by the overall reload category and cleanup result. If generation fails before any fixture completes, it reports that explicitly.

The production generated pack is not modified. Production 1.1.0 does not supply the self-test with a live reload handle, so a successful isolated generation truthfully reports `restart-required`; the command validates generation and conditions, not live Hytale reload. A cancelled or failed test reports truthful generation and cleanup state. Cleanup failure retains the exact run as evidence.

## Recovery checklist

1. Run `/patchwork status` and capture the active provider, epoch, roots, manifest state, integrity state, and affected target categories.
2. Inspect the server log for the first Patchwork failure associated with that epoch.
3. For `restart-required`, restart normally; do not force a generic reload.
4. For `stale`, verify the host adapter or Hytale event that should confirm the exact target and hash.
5. For `rollback-failed`, stop further reload attempts, preserve Patchwork diagnostic directories, and restart from known-good assets.
6. For `RECOVERY_REQUIRED`, let the owning plugin's retained lifecycle handle retry shutdown/unregister. Do not install or hot-swap another runtime until cleanup succeeds.
7. If a standalone copy unexpectedly wins, compare its runtime version with the embedded candidates shown by status. Election is version-first; standalone is only an equal-version tie-breaker.

## Filesystem boundary

Patchwork rejects observable path escape, symbolic-link, reparse-like, identity, and content changes around condition and generated-output reads. On platforms without descriptor-relative directory traversal, a precisely timed hostile same-process swap cannot be eliminated. This is an integrity boundary, not a sandbox: a malicious Java mod already has equivalent process filesystem access.
