# Patchwork Agent Guidelines

- Use Git Bash for repository commands and do not leave processes running.
- Keep the reusable runtime free of Hytale plugin identity files; the standalone module owns the plugin manifest and shaded artifact.
- Use Java 25 and preserve the Hytale system dependency path pattern in Maven builds.
- Run `./mvnw.cmd test` after code changes and `./mvnw.cmd -pl standalone -am verify` after packaging changes.
- Keep docs and `CHANGELOG.md` aligned with user-facing behavior. Do not claim unfinished runtime functionality.
