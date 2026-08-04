---
title: "Glossary"
order: 4
published: true
draft: false
---

# Glossary

Parent: [Reference Library](/mod/patchwork/reference-library) | [Home](/mod/patchwork/home)

**Asset** — A JSON file used by Hytale or a mod to define game data.

**Asset pack** — A collection of assets supplied by a mod or other loaded provider.

**Condition** — A `When` rule that decides whether a patch is eligible for one target.

**Conflict** — Two different definitions successfully produce the same kind of effect at the same target path. It is a reportable overlap, not an ownership system.

**Definition** — One Patchwork JSON file containing a target and ordered operations.

**Generated pack** — Patchwork's separate asset pack containing completed copies of successfully patched targets.

**Glob** — A `glob:` target selector using `*`, `**`, or `?` to select matching asset paths.

**JSON Pointer** — A slash-led address inside JSON, such as `/Parameters/Enabled`.

**Last-known-good** — Previously published generated output retained as recovery evidence while a replacement is staged and verified.

**Neutral definition** — The current marker-free Patchwork format used for new definitions under `Server/Patchwork/Patches/`.

**Operation** — One requested JSON change, such as `Add`, `Merge`, or `Replace`.

**Patch** — The practical name for a definition and the changes it requests.

**Runtime election** — The process that chooses exactly one active Patchwork copy when standalone and embedded copies are present.

**Target** — The original asset path that Patchwork copies and changes for one patch application.

**Target adapter** — An embedding plugin's declared, supported route for confirming a live reload of a target.
