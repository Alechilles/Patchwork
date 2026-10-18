# Headless Preview

`patchwork-preview` is a command-line tool for asset tooling and CI. It runs one real Patchwork
generation pass over pack roots you name, outside Hytale, and writes a JSON report. It is not a
Hytale plugin, it never writes a generated pack, and it is not published as a Maven artifact.

Build it with:

```bash
./mvnw -pl preview -am package
```

The runnable jar is `preview/target/patchwork-preview-<version>.jar`.

## Usage

```bash
java -jar preview/target/patchwork-preview-1.4.2.jar \
  --assets <install>/Assets.zip \
  --pack <first mod root> --pack <second mod root> \
  --installed "Alechilles:Alec's Tamework!=5.4.0" \
  --out preview.json
```

| Option | Meaning |
| --- | --- |
| `--assets <Assets.zip or dir>` | Base game assets, loaded first as `Hytale:Hytale`. |
| `--pack <dir or zip>` | An asset pack with `manifest.json`. Repeat in load order; a later pack wins a shared path. The pack ID is `Group:Name`, as Hytale builds it. |
| `--installed <id[=version]>` | An installed plugin that has no pack here, for `ModInstalled`, `ModVersion` and the legacy `Server/Tamework/Patches` root. Every pack is already installed. |
| `--server-version <version>` | The server version for `ServerVersion` conditions. Without it, the version is read from `Server/HytaleServer.jar` next to `--assets`, or from `--server-jar`. |
| `--mod-data <id>=<dir>` | A plugin data directory for `ModData` condition sources. |
| `--target <asset path>` | Report only this target. Repeatable. |
| `--no-documents` | Leave the final documents out of the report. |
| `--out <file>` | Write the report to a file instead of standard output. |

Host macros are not available outside the host plugin, so a definition that uses `Macro` fails its
target here, as it would in a runtime without that contribution.

## Report

The final documents, rejected targets and conflict rows come from the same generation service the
runtime uses. A second pass over the same snapshot, with the same scanner, condition evaluator,
engine and conflict analyzer, records why each definition applied or not and what each side of a
conflict wrote. Unlike `/patchwork conflicts`, the report includes values, because it is for local
tooling.

- `overlays`: one row per definition and concrete target, with `outcome` and `reason`:
  `applied`, `partially-applied` (some optional operations skipped), `all-operations-skipped`,
  `gated` (its `When` did not match), `condition-failed`, `failed` (a required operation failed),
  `conflict-rejected` (its `ConflictPolicy` is `Reject`), `target-missing`, `target-rejected`
  (another definition stopped the target) or `no-match` (a `glob:` selector matched nothing).
- `targets`: one row per target with `provider` (the pack whose copy was patched), `status`
  (`patched`, `unchanged`, `rejected` or `missing`), the applied patch IDs in order and the final
  `document`.
- `conflicts`: each overlap with `earlier` and `later` (pack, patch, operation and the value right
  after that definition applied), and the `final` value in the generated document.
- `scanSkipped`, `scanFailures` and `warnings`.
