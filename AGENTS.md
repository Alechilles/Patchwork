# Patchwork Agent Guidelines

- Use Git Bash for repository commands and do not leave processes running.
- Keep the reusable runtime free of Hytale plugin identity files; the standalone module owns the plugin manifest and shaded artifact.
- Use Java 25. The runtime has a compile-only Hytale server JAR path; the standalone module uses the AzureDoom Hytale Gradle plugin.
- Run `bash ../gradlew :patchwork:runtime:test :patchwork:standalone:test` after code changes and `bash ../gradlew :patchwork:standalone:build` after packaging changes.
- Patchwork's standalone JAR is distributable but is not part of the default shared `runAllMods` set: Tamework supplies its shaded runtime there. Do not re-add a second Patchwork plugin to `run\mods`.
- Keep docs and `CHANGELOG.md` aligned with user-facing behavior. Do not claim unfinished runtime functionality.

## Durable Product and Runtime Contracts

- Treat native Hytale Asset Editor usability as part of every public authoring feature. Ship the native codec/schema, beginner-facing labels and nonblank field help, portable schema/capabilities, conformance fixtures, and schema tests in the same change.
- Keep portable Patchwork JSON editor-independent and lossless. Do not serialize editor-only wrappers, discriminators, or metadata, and preserve accepted legacy casing, `null`, numeric precision, and opaque extensions when reopening and saving.
- Evolve public patch syntax additively. Do not reinterpret existing operations or require authors to choose a format/version without an explicitly approved compatibility design. Unknown structural syntax in modern neutral definitions must fail closed rather than partially apply; preserve the documented parsing behavior of existing versioned and legacy definitions.
- Keep generation deterministic and based on one immutable source snapshot. Exclude Patchwork's generated pack from inputs, never mutate source assets, and isolate applicability or validation failures to affected targets.
- Preserve last-known-good generated content when an edited definition becomes invalid; do not turn a transient authoring error into unrelated asset removal.
- Report hot reload only after evidence from Hytale confirms the expected store, asset, provider, and generation state. A disk write or uncorrelated event is not proof; uncertain cases must say restart required.
- Test the actual generated authoring schema and portable round trips, not only Java model construction. Prefer guided finite choices and recursive typed entry wherever the portable grammar supports them.
