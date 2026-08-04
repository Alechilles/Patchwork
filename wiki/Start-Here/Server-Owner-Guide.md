---
title: "Server Owner Guide"
order: 5
published: true
draft: false
---

# Server Owner Guide

Parent: [Start Here](/mod/patchwork/start-here) | [Home](/mod/patchwork/home)

Most server owners do not need to author patches. Patchwork normally works quietly when a mod includes patch definitions or lists Patchwork as a dependency.

## Install It When Needed

Install the released standalone Patchwork jar in your server's normal Hytale mods location when a mod's installation instructions say Patchwork is required. Restart the server after adding or updating it.

Some Java mods embed a compatible Patchwork runtime. In that case, do not install an extra jar unless that mod specifically asks you to. If both are present, Patchwork elects one active runtime and keeps the other passive, preventing duplicate generation and duplicate commands.

## What You Can Safely Do

- Use `/patchwork status` to inspect the active runtime and the latest result.
- Use `/patchwork conflicts` to see redacted overlap reports.
- Use `/patchwork reload` after an author changes relevant directory-pack files.
- Restart normally when status reports `restart-required`.
- Keep the server log and Patchwork diagnostic folders when asking an author for help.

All Patchwork commands require `patchwork.admin`, which defaults to the `hytale:Admin` group.

## What Not to Do

- Do not edit files in Patchwork's generated pack by hand.
- Do not force a generic Hytale asset reload because a generated file exists.
- Do not delete diagnostic evidence while a failed reload is being investigated.
- Do not assume two copies of Patchwork should both be active.

See [Commands and Status](/mod/patchwork/commands-and-status) for the available commands and [Troubleshooting](/mod/patchwork/troubleshooting) for a recovery checklist.
