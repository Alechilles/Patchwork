---
title: "Compatibility and Versions"
order: 3
published: true
draft: false
---

# Compatibility and Versions

Parent: [Reference Library](/mod/patchwork/reference-library) | [Home](/mod/patchwork/home)

## Where Patchwork Reads Definitions

New definitions belong here:

```text
Server/Patchwork/Patches/**/*.json
```

When `Alechilles:Alec's Tamework!` is installed, Patchwork also reads the legacy compatibility location:

```text
Server/Tamework/Patches/**/*.json
```

Within one source pack, an enabled neutral definition shadows an enabled legacy definition with the same pack ID, patch ID, and target. Disabled definitions are skipped; they do not collide or shadow anything.

## New, Legacy, and Explicit Versioned Files

New Patchwork definitions are **neutral**: omit `FormatVersion` and omit `RequireFormat`. Their closed structure means a runtime that does not recognize a new operation or field reports an installation/version error instead of silently producing a partial asset.

Explicit format 1 and format 2 definitions remain readable for compatibility. Do not add version markers to a new file merely to be explicit. If you are maintaining an explicit format 2 file, it must set `"FormatVersion": 2` and start its operations with exactly this sentinel:

```json
{ "Op": "RequireFormat", "Version": 2 }
```

The sentinel must be operation zero and cannot carry ordinary operation fields. Format 1 retains its historical behavior; do not assume it acquires newer pointer or matcher rules automatically.

## One Active Runtime

Patchwork may arrive as a standalone jar and as one or more embedded runtime copies. Only compatible candidates participate. The elected active copy is chosen by:

1. highest compatible Patchwork version;
2. standalone before embedded when versions are equal;
3. provider plugin ID; then
4. normalized source-jar path.

Only the winner scans patches, generates output, registers commands, and owns automatic reload work. Passive copies remain visible in `/patchwork status` and their host contributions are replayed to the winner.

This means an embedded 1.3.0 copy beats a standalone 1.0.0 copy, while a standalone 1.3.0 copy beats an embedded 1.3.0 copy.

## Embedded telemetry compatibility

Patchwork 1.3.x carries Alec's Telemetry runtime 1.1.x transitively. Its namespaced
`patchwork` contribution is hosted-only and uses independent consent from any conventional
host project. A conventional base project wins a same-ID collision; a retired contribution is
not live-promoted to an already-registered fallback in the 1.3.x MVP. A server restart is
required for that change.
