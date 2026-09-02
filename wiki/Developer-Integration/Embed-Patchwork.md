---
title: "Embed Patchwork"
order: 2
published: true
draft: false
---

# Embed Patchwork

Parent: [Developer Integration](/mod/patchwork/developer-integration) | [Home](/mod/patchwork/home)

Embedding lets a Java plugin bring Patchwork with it, so server owners do not need a separate jar for that plugin's patch definitions. An embedded copy participates in the same shared runtime election as the standalone mod; it is never a private patch engine.

## Add the Runtime Dependency

Depend on `patchwork-runtime`, not `patchwork-standalone`:

```xml
<dependency>
  <groupId>com.alechilles</groupId>
  <artifactId>patchwork-runtime</artifactId>
  <version>1.4.1</version>
</dependency>
```

Patchwork 1.4.1 carries `com.alechilles:beacon-runtime:2.0.0` transitively. The
namespaced Patchwork project is hosted-only, has independent consent, and supports uncaught
exception crash reports plus anonymous aggregate stats. Initialization, writes, and shutdown are
best-effort; telemetry failures never block Patchwork. Do not add a second standalone Beacon
plugin.

Beacon 2.0.0 is a breaking runtime rename. Change imports from
`com.alechilles.alecstelemetry` to `com.alechilles.beacon` and move namespaced descriptors from
`META-INF/alecs-telemetry/projects/` to `META-INF/beacon/projects/`.

Shade the runtime without relocating the `com.alechilles.patchwork` package. Do not add a second Hytale `manifest.json` to your plugin.

## Use the Stable Embedded Surface

Only import types from `com.alechilles.patchwork.embedded`. Discovery, generation, conditions, commands, and other runtime packages are internal implementation details.

```java
public final class ExamplePlugin extends JavaPlugin {
    private EmbeddedPatchworkService patchwork;
    private PatchworkContributionHandle contribution;

    @Override
    protected void setup() {
        patchwork = EmbeddedPatchworkBootstrap.bootstrap(this);
        patchwork.start();
        contribution = patchwork.registerContribution(new ExampleContribution());
    }

    @Override
    protected void shutdown() {
        if (contribution != null) contribution.close();
        if (patchwork != null) patchwork.close();
    }
}
```

Retain the exact returned handles. Close the contribution before the service. A close can fail while Patchwork is safely draining or recovering; keep the handle and retry it rather than discarding it.

Start the service and register contributions during the host plugin's `setup()` method. Hytale
dispatches its one startup asset-load event after plugin setup and before plugin start. Starting
Patchwork later misses startup generation, and registering a contribution later excludes its
macros and adapters from that startup pass.

`generatedPatchRoot()` returns the current elected runtime's generated-pack root. It may belong to another plugin's elected copy, not to your embedded jar.

## Do Not Re-register After Election Changes

Patchwork keeps contributions from passive embedded services and replays them to a new winner. Your plugin should not bootstrap or register everything again merely because status reports a different winner.

See [Macros and Reload Adapters](/mod/patchwork/macros-and-reload-adapters) to contribute host-specific behavior.

Patchwork's embedded telemetry descriptor is loaded from
`META-INF/beacon/projects/patchwork.json` and uses logical owner
`Alechilles:Patchwork`. It supports only the Crash and Stats consent categories and does not
consume the host's `Server/Beacon/project.json` slot. Base-project ID collisions are rejected,
and same-ID fallback promotion requires a server restart in the 1.4.1 release.
