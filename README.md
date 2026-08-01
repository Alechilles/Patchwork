# Patchwork

Patchwork is an extracted, reusable Hytale asset-patching runtime. This initial scaffold establishes its artifact boundaries; the runtime is not functional yet.

## Maven coordinates

- Runtime library: `com.alechilles:patchwork-runtime:1.0.0`
- Standalone plugin: `com.alechilles:patchwork-standalone:1.0.0`

## Installation modes

- Add the runtime library as a dependency when another plugin supplies its own Hytale bootstrap and manifest.
- Install `patchwork-standalone-1.0.0.jar` as the standalone Hytale plugin when its bootstrap is available in a later release.

## License

Patchwork is source-available under the [Patchwork Source Available License 1.0](LICENSE.txt).
