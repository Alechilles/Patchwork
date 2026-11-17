# Patchwork Agent Guidelines

- Keep the reusable runtime free of Hytale plugin identity files; the standalone module owns the plugin manifest and shaded artifact.
- Use Java 25. The runtime has a compile-only Hytale server JAR path; the standalone module uses the AzureDoom Hytale Gradle plugin.
- Tests: `bash ../gradlew -p .. :patchwork:runtime:test :patchwork:standalone:test` (run a focused test for a bounded change, both suites for shared behavior or public contracts); packaging: `bash ../gradlew -p .. :patchwork:standalone:build`. These run from the canonical checkout through the parent workspace. A worktree has no `../gradlew`: use a workspace that includes that checkout, or report that validation was unavailable, never results from another checkout.
- Patchwork's standalone JAR is distributable but is not part of the default shared `runAllMods` set: Tamework supplies its shaded runtime there. Do not re-add a second Patchwork plugin to `run\mods`.
- Keep docs and `CHANGELOG.md` aligned with user-facing behavior. Do not claim unfinished runtime functionality.

## Durable Product and Runtime Contracts

- Preserve native Hytale Asset Editor usability for public authoring features. Keep affected native codecs/schemas, beginner-facing labels and nonblank field help, and portable schemas/capabilities aligned in the same change. Add conformance fixtures and tests only for a specific regression or compatibility case; a labels/help-only change needs none.
- Keep portable Patchwork JSON editor-independent and lossless. Do not serialize editor-only wrappers, discriminators, or metadata, and preserve accepted legacy casing, `null`, numeric precision, and opaque extensions when reopening and saving.
- Evolve public patch syntax additively. Do not reinterpret existing operations or require authors to choose a format/version without an explicitly approved compatibility design. Unknown structural syntax in modern neutral definitions must fail closed rather than partially apply; preserve the documented parsing behavior of existing versioned and legacy definitions.
- Keep generation deterministic and based on one immutable source snapshot. Exclude Patchwork's generated pack from inputs, never mutate source assets, and isolate applicability or validation failures to affected targets.
- Preserve last-known-good generated content when an edited definition becomes invalid; do not turn a transient authoring error into unrelated asset removal.
- Report hot reload only after evidence from Hytale confirms the expected store, asset, provider, and generation state. A disk write or uncorrelated event is not proof; uncertain cases must say restart required.
- When schema or round-trip behavior needs regression coverage, exercise the actual generated schema or portable round trip, not only Java model construction. Prefer guided finite choices and recursive typed entry wherever the portable grammar supports them.
