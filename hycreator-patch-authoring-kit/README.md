# Patchwork neutral authoring kit for HyCreator

This is the schema handoff for HyCreator's Patchwork support. New definitions use the marker-free neutral language: do not emit `FormatVersion` or a `RequireFormat` operation.

- `patch-definition.schema.json` is the closed JSON Schema for portable patch definitions.
- `capabilities.json` declares the supported operations and editor capabilities.

The schema includes the advanced operations `MergeMatching`, `UpsertMatching`, `OverlayFromAsset`, and `MergeObjectFromAsset`. Keep portable JSON lossless: preserve `$Comment`, `null`, numeric precision, and unknown macro option contents when reopening and saving.
