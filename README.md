# Patchwork

Patchwork is a reusable Hytale asset-patching runtime. Mods and content packs can declare deterministic JSON patches without owning a private patch engine, while Java plugins can embed the same runtime and contribute host-specific macros or live-reload adapters.

Patchwork 1.0.0 supports two installation modes:

- Install `patchwork-standalone-1.0.0.jar` as a Hytale server mod.
- Embed `com.alechilles:patchwork-runtime:1.0.0` in another Java plugin.

Every copy participates in one process-wide election. The highest compatible runtime version wins; standalone wins only an equal-version tie. Passive copies forward their contributions to the winner and do not scan, generate, register commands, or own workers.

## Patch roots

Put new definitions under:

```text
Server/Patchwork/Patches/**/*.json
```

Patchwork also reads `Server/Tamework/Patches/**/*.json` when Alec's Tamework is installed. That legacy root exists for compatibility; a matching neutral-root definition wins.

Definitions can patch one `Target` or several `Targets`, use `Add`, `Merge`, `Replace`, `Remove`, and `Insert` operations, and conditionally apply through installed-mod, version, asset, target, or JSON document checks. JSON conditions can read the target, another asset, or JSON below a registered Java mod's data directory.

See [Patch Format](docs/Patch-Format.md) for the complete schema and examples.

## Native asset editing

The standalone Patchwork plugin registers `Server/Patchwork/Patches/**/*.json` as a native Hytale asset type. Hytale's Asset Editor can therefore discover, create, open, structurally edit, validate, and save the same portable definitions consumed by Patchwork. The generated codec schema exposes definition fields and typed operation fields; deliberately free-form patch values, matchers, conditions, and macro options remain JSON-shaped data inside those structured records. Native open/save preserves explicit `null`, precise JSON numbers, and accepted format-1 extension fields.

Native registration is an authoring and validation surface, not a second patch input. The existing `PatchScanner` remains the sole source used for generation, so a definition loaded by Hytale's asset store is still applied exactly once. Saving is an ordinary asset save and does not add a separate Patchwork apply/restart workflow.

## Administration

The elected runtime registers:

```text
/patchwork status
/patchwork reload
/patchwork selftest
```

All three require `patchwork.admin` and default to the `hytale:Admin` group.

Generation occurs during the early startup asset-load phase and on an authorized `/patchwork reload`. Native Asset Editor registration does not by itself generate or apply a saved definition.

See [Operations](docs/Operations.md) for status fields, reload outcomes, generated paths, quarantine, and recovery guidance.

## Embedding

Maven dependency:

```xml
<dependency>
  <groupId>com.alechilles</groupId>
  <artifactId>patchwork-runtime</artifactId>
  <version>1.0.0</version>
</dependency>
```

Bootstrap from the host plugin's lifecycle with `EmbeddedPatchworkBootstrap.bootstrap(plugin)`, retain the returned service, start it, register any contribution, and close the contribution before the service. See [Embedding](docs/Embedding.md) for the complete contract.

## Runtime election

Multiple standalone and embedded copies are supported. Election ordering, ABI eligibility, lifecycle handoff, and recovery behavior are documented in [Runtime Election](docs/Runtime-Election.md).

## Artifacts

- Reusable runtime: `com.alechilles:patchwork-runtime:1.0.0`
- Standalone plugin: `com.alechilles:patchwork-standalone:1.0.0`

## License

Patchwork is source-available under the [Patchwork Source Available License 1.0](LICENSE.txt).
