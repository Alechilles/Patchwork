---
title: "Macros and Reload Adapters"
order: 3
published: true
draft: false
---

# Macros and Reload Adapters

Parent: [Developer Integration](/mod/patchwork/developer-integration) | [Home](/mod/patchwork/home)

An embedding plugin can contribute a `PatchworkHostContribution` containing macro providers and target reload adapters. Give every host plugin, macro, and adapter a stable unique ID.

## Macros

A patch author can write a `Macro` operation when a host provides that macro ID:

```json
{
  "Op": "Macro",
  "Macro": "ExampleMacro",
  "Options": { "Mode": "Fast" }
}
```

The host receives a defensive copy of the original macro operation and returns an array of ordinary Patchwork operations. Validate your own `Options` and never mutate the input. A missing macro provider fails the affected target, so authors do not get a partially modified asset.

```java
public final class ExampleMacro implements PatchworkMacroProvider {
    @Override public String macroId() { return "ExampleMacro"; }

    @Override public JsonArray expand(JsonObject operation) {
        JsonArray expanded = new JsonArray();
        expanded.add(JsonParser.parseString(
            "{\"Id\":\"set-enabled\",\"Op\":\"Replace\",\"Path\":\"/Enabled\",\"Value\":true}"));
        return expanded;
    }
}
```

Patchwork has no universal macro-options descriptor. A provider may publish its own descriptors below `Server/Patchwork/Authoring/Macros/**/*.json`.

## Reload Adapters

An adapter declares exactly which targets it supports and uses the host's existing authorized reload route. It must not call a generic Hytale asset reload.

```java
public final class ExampleAdapter implements PatchworkTargetAdapter {
    @Override public String adapterId() { return "Example:Assets"; }
    @Override public boolean supports(String target) {
        return target.startsWith("Server/Example/");
    }
    @Override public CompletionStage<PatchworkReloadResult> reload(
            PatchworkReloadRequest request) {
        return CompletableFuture.completedFuture(new PatchworkReloadResult(
            adapterId(),
            request.targets().stream().map(PatchworkTargetExpectation::target).toList(),
            List.of(), List.of()));
    }
}
```

In Patchwork 1.2.1, each adapter request contains exactly one target expectation, even though the request shape is a list for future compatibility. Return exact target lists for reloaded, restart-required, and failed outcomes.

To confirm live state, report an observation only for the supplied pending epoch, adapter ID, target, and expected hash. An old or unrelated asset event must never confirm a current transaction.
