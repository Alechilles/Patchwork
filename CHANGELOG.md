# Changelog

## 1.1.0 - Unreleased

### Added

- Added deterministic JSON asset patches with single- and multi-target definitions, five built-in operations, host macros, and per-target failure isolation.
- Added installed-mod, version, asset, target, and composable JSON conditions. JSON sources can read the target, another asset, or JSON under a registered Java mod data directory.
- Added the neutral `Server/Patchwork/Patches` root and compatibility discovery for `Server/Tamework/Patches` while Alec's Tamework is installed.
- Added reusable embedded runtime APIs for host macro providers, target reload adapters, and exact reload observations.
- Added standalone and embedded runtime election: the latest compatible version wins, with standalone preferred only for equal versions.
- Added startup publication with last-known-good quarantine, deterministic generated manifests, and recovery evidence.
- Added target-local reload transactions with host-adapter reload, restart-required, stale, rollback-failed, skipped, and failed outcomes. Standalone 1.1.0 conservatively requires restart because it does not provide a built-in live target route.
- Added `/patchwork status`, `/patchwork reload`, and `/patchwork selftest` for `patchwork.admin` administrators.
- Added an isolated self-test that exercises every built-in patch operation and mod-data-conditioned generation without changing production output, reports each completed fixture's in-game pass/fail result, and truthfully reports restart-required for its isolated result.
- Added Patchwork format 2 support with strict pointer and matcher semantics, `RequireFormat` compatibility guarding, matcher-based array operations, portable target-provider conditions, and a versioned authoring kit; format 1 remains an explicit legacy-compatible mode.
- Added standalone native Hytale asset registration for `Server/Patchwork/Patches`, including codec-generated definition and operation schemas, lossless portable JSON open/save, and validation through the portable Patchwork parser. The existing scanner remains the sole generation source, preventing duplicate application.
- Added beginner-facing documentation for every native Patchwork definition and operation field, plus documented editor dropdowns for operation type, array position, and match policy.
- Added guided recursive native-editor schemas for `When` conditions, `$Equals`/`$Contains` matchers, arbitrary JSON `Value` fields, and object-only macro `Options`. This changes editor guidance only; portable patch JSON and runtime behavior are unchanged.

### Fixed

- Added the missing standalone mod icon at the artifact root beside `manifest.json`.
- Fixed recursive Patchwork authoring schemas failing to mount in Hytale's Asset Editor because their shared definitions were placed in the editor-omitted `other.json` schema file.
- Fixed target resolution for registered asset packs installed through symbolic links or Windows junctions.

### Removed

- Retired the Tamework-specific `TameworkSetting` condition. Use `JsonPathEquals` with a `ModData` source instead.
