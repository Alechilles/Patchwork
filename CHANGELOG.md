# Changelog

## 1.0.0 - Unreleased

### Added

- Added deterministic JSON asset patches with single- and multi-target definitions, five built-in operations, host macros, and per-target failure isolation.
- Added installed-mod, version, asset, target, and composable JSON conditions. JSON sources can read the target, another asset, or JSON under a registered Java mod data directory.
- Added the neutral `Server/Patchwork/Patches` root and compatibility discovery for `Server/Tamework/Patches` while Alec's Tamework is installed.
- Added reusable embedded runtime APIs for host macro providers, target reload adapters, and exact reload observations.
- Added standalone and embedded runtime election: the latest compatible version wins, with standalone preferred only for equal versions.
- Added startup publication with last-known-good quarantine, deterministic generated manifests, and recovery evidence.
- Added target-local reload transactions with host-adapter reload, restart-required, stale, rollback-failed, skipped, and failed outcomes. Standalone 1.0.0 conservatively requires restart because it does not provide a built-in live target route.
- Added `/patchwork status`, `/patchwork reload`, and `/patchwork selftest` for `patchwork.admin` administrators.
- Added an isolated self-test that exercises ordinary and mod-data-conditioned generation without changing production output and truthfully reports restart-required for its isolated result.
- Added Patchwork format 2 support with strict pointer and matcher semantics, `RequireFormat` compatibility guarding, matcher-based array operations, portable target-provider conditions, and a versioned authoring kit; format 1 remains an explicit legacy-compatible mode.
- Added standalone native Hytale asset registration for `Server/Patchwork/Patches`, including codec-generated definition and operation schemas, lossless portable JSON open/save, and validation through the portable Patchwork parser. The existing scanner remains the sole generation source, preventing duplicate application.

### Fixed

- Added the missing standalone mod icon at the artifact root beside `manifest.json`.
- Fixed target resolution for registered asset packs installed through symbolic links or Windows junctions.

### Removed

- Retired the Tamework-specific `TameworkSetting` condition. Use `JsonPathEquals` with a `ModData` source instead.
