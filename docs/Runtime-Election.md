# Runtime Election

Every Patchwork runtime copy contends through one protocol-neutral JVM property. This prevents a standalone jar and several embedded jars from starting independent patch engines.

## Ordering

Only candidates compatible with coordinator ABI 1 are eligible. Eligible candidates are ordered by:

1. Highest semantic runtime version.
2. `STANDALONE` before `EMBEDDED` only when versions are equal.
3. Provider plugin identifier.
4. Normalized source-jar path.

Consequences:

- Standalone 1.1.0 beats embedded 1.0.0.
- Embedded 1.1.0 beats standalone 1.0.0.
- Standalone 1.0.0 beats embedded 1.0.0.
- An ABI-incompatible copy remains visible and passive; it does not create another owner.

## Active and passive copies

The active candidate alone may:

- scan patches and source assets;
- generate or publish the generated pack;
- register `/patchwork` commands;
- own reload, observation, and self-test work;
- accept host contribution calls.

Passive candidates retain their registration and appear in `/patchwork status`. Host contributions registered through passive embedded services are stored by the global coordinator and replayed to the active runtime.

## Ownership handoff

Each activation receives a monotonically increasing epoch. On a winner change, Patchwork:

1. fences the old epoch;
2. stops new work and drains admitted reload, self-test, contribution, and early-load work;
3. unregisters the old command tree and early-load callback;
4. deactivates the old runtime;
5. activates the new candidate;
6. replays current host contributions;
7. registers the new callback and command tree.

The old command tree is removed before the new one is registered. Work that completes after its epoch was fenced cannot publish status or generated output.

## Provider replacement and removal

Registrations use opaque tokens. Replacing the same provider requires its exact incumbent token, so an old plugin handle cannot remove a newer bridge. Closing a passive candidate removes only that candidate. Closing the active candidate elects the next eligible copy after the old one drains.

## Failed activation and fail-closed recovery

If a replacement fails to activate, Patchwork cleans it and restores the prior owner or elects the next eligible candidate.

If cleanup itself cannot be proven complete, the coordinator enters `RECOVERY_REQUIRED`. It permits only exact cleanup retry from the retained registration token; publication and unrelated mutations are rejected. This prevents a stale callback, command, or worker from coexisting with a new owner.

ABI-1 handles remain safe in this state: registration returns the retained token, and publishing that exact recovery token throws, so startup fails visibly while the handle still owns the token needed by its later close retry.

## Inspecting election

`/patchwork status` reports:

- active runtime version, origin, provider plugin/version, coordinator ABI, and source jar;
- passive candidates and election reasons;
- registered contribution IDs, macro IDs, and adapter IDs;
- neutral and legacy root state;
- current ownership epoch.

Tokens and condition values are never printed.
