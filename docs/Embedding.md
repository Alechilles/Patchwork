# Embedding Patchwork

Patchwork can be shaded into another Hytale Java plugin. Embedded and standalone copies use the same runtime and election; embedding does not create a private patch owner.

## Maven dependency

```xml
<dependency>
  <groupId>com.alechilles</groupId>
  <artifactId>patchwork-runtime</artifactId>
  <version>1.3.0</version>
</dependency>
```

Patchwork 1.3.0 brings `com.alechilles:alecstelemetry-runtime:1.1.0` transitively. It
registers the namespaced `patchwork` project automatically when the host starts. The project
uses Alec's hosted destination and has independent consent from the host's telemetry project;
no second standalone Telemetry plugin is needed. Telemetry initialization and writes are
best-effort and never block Patchwork lifecycle, generation, or reload operations.

Shade `patchwork-runtime` without relocating `com.alechilles.patchwork`. Do not embed `patchwork-standalone`, and do not add a second Hytale `manifest.json`.

Host code may import only the stable `com.alechilles.patchwork.embedded` surface. Coordinator, generation, discovery, condition, reload, command, and self-test packages are runtime internals.

## Lifecycle

```java
public final class ExamplePlugin extends JavaPlugin {
    private EmbeddedPatchworkService patchwork;
    private PatchworkContributionHandle contribution;

    public ExamplePlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        patchwork = EmbeddedPatchworkBootstrap.bootstrap(this);
    }

    @Override
    protected void start() {
        patchwork.start();
        contribution = patchwork.registerContribution(new ExampleContribution());
    }

    @Override
    protected void shutdown() {
        if (contribution != null) {
            contribution.close();
            contribution = null;
        }
        if (patchwork != null) {
            patchwork.close();
            patchwork = null;
        }
    }
}
```

Retain the exact returned handles. Close contributions before the service. A lifecycle close can fail while Patchwork is fail-closed or draining; keep the handle and retry instead of discarding it.

`generatedPatchRoot()` returns the elected winner's generated pack root. It is not necessarily owned by the embedding copy.

## Stable API surface

Patchwork 1.3.0 exposes these host-facing contracts from `com.alechilles.patchwork.embedded`:

```java
public final class EmbeddedPatchworkBootstrap {
    public static EmbeddedPatchworkService bootstrap(JavaPlugin plugin);
}

public interface EmbeddedPatchworkService extends AutoCloseable {
    void start();
    PatchworkContributionHandle registerContribution(PatchworkHostContribution contribution);
    Path generatedPatchRoot();
    void recordObservation(PatchworkReloadObservation observation);
    void close();
}

public interface PatchworkContributionHandle extends AutoCloseable {
    void close();
}

public interface PatchworkHostContribution {
    String hostPluginIdentifier();
    String contributionVersion();
    List<PatchworkMacroProvider> macroProviders();
    List<PatchworkTargetAdapter> targetAdapters();
}

public interface PatchworkMacroProvider {
    String macroId();
    JsonArray expand(JsonObject operation);
}

public interface PatchworkTargetAdapter {
    String adapterId();
    boolean supports(String target);
    CompletionStage<PatchworkReloadResult> reload(PatchworkReloadRequest request);
}

public record PatchworkReloadRequest(long epoch, List<PatchworkTargetExpectation> targets) { }
public record PatchworkTargetExpectation(String target, String expectedHash, boolean removal) { }
public record PatchworkReloadResult(String adapterId, List<String> reloadedTargets,
                                    List<String> restartRequiredTargets, List<String> failures) { }
public record PatchworkReloadObservation(long epoch, String adapterId, String target,
                                         String expectedHash, PatchworkObservationOutcome outcome) { }
public enum PatchworkObservationOutcome { LOADED, REMOVED, FAILED }
```

In 1.2.1, each adapter invocation contains exactly one `PatchworkTargetExpectation`, even though the request type uses a list for forward compatibility. Implementations must handle the current singleton contract and must not assume unrelated targets are batched together.

## Contributions

A contribution declares stable macro and target-adapter IDs:

```java
public final class ExampleContribution implements PatchworkHostContribution {
    @Override public String hostPluginIdentifier() { return "Example:Mod"; }
    @Override public String contributionVersion() { return "1.2.1"; }
    @Override public List<PatchworkMacroProvider> macroProviders() {
        return List.of(new ExampleMacro());
    }
    @Override public List<PatchworkTargetAdapter> targetAdapters() {
        return List.of(new ExampleAdapter());
    }
}
```

Contribution metadata is captured at registration time. Return immutable or defensively copied lists. Macro IDs share a case-insensitive namespace; adapter IDs must also be unique.

When election changes, the coordinator replays current contributions to the new winner. The host must not re-bootstrap or re-register merely because another copy won.

## Macro providers

```java
public final class ExampleMacro implements PatchworkMacroProvider {
    @Override public String macroId() { return "ExampleMacro"; }

    @Override
    public JsonArray expand(JsonObject operation) {
        JsonArray expanded = new JsonArray();
        expanded.add(JsonParser.parseString("""
            {"Id":"example-expanded","Op":"Replace","Path":"/Enabled","Value":true}
            """));
        return expanded;
    }
}
```

The macro boundary receives the original macro operation as a defensive JSON object and returns an array of ordinary Patchwork operations. Validate host-specific `Options` and do not mutate the input.

## Target adapters

Adapters provide only explicitly supported host reload routes. They must not call a generic Hytale asset reload.

```java
public final class ExampleAdapter implements PatchworkTargetAdapter {
    @Override public String adapterId() { return "Example:Assets"; }

    @Override
    public boolean supports(String target) {
        return target.startsWith("Server/Example/");
    }

    @Override
    public CompletionStage<PatchworkReloadResult> reload(PatchworkReloadRequest request) {
        // Schedule through the host's existing authorized thread mechanism.
        return CompletableFuture.completedFuture(new PatchworkReloadResult(
            adapterId(),
            request.targets().stream().map(PatchworkTargetExpectation::target).toList(),
            List.of(),
            List.of()
        ));
    }
}
```

Each request carries one coordinator epoch and one immutable target expectation in 1.3.0. Return exact reloaded, restart-required, and failed target lists.

## Telemetry contribution contract

The Patchwork runtime's descriptor is namespaced at
`META-INF/alecs-telemetry/projects/patchwork.json`, so it can be shaded beside a host's
conventional descriptor. It declares the logical owner `Alechilles:Patchwork`, project ID
`patchwork`, and runtime version `1.3.0`. Do not copy it to `Server/Telemetry/project.json` or
replace it with a custom endpoint: contributed projects are hosted-only in 1.3.x.

If a host already contains another conventional project with the same logical ID, the base
project remains authoritative and the Patchwork contribution is rejected. If an active
contribution retires, an already-registered same-ID fallback is not promoted live in this
release; a server restart is required. This keeps queued destinations and
consent ownership stable.

For live confirmation, record only observations correlated to a pending expectation supplied by Patchwork:

```java
patchwork.recordObservation(new PatchworkReloadObservation(
    epoch,
    "Example:Assets",
    target,
    expectedHash,
    PatchworkObservationOutcome.LOADED
));
```

Outcomes are `LOADED`, `REMOVED`, and `FAILED`. Never invent an expected hash from an unrelated asset event, and never let an old epoch confirm a current transaction.

## Standalone wrapper

The shipped standalone plugin follows the same hierarchy through `StandalonePatchworkBootstrap.bootstrapStandalone(plugin)`. Hosts embedding Patchwork should use `EmbeddedPatchworkBootstrap`, not the standalone service.
