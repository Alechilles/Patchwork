---
title: "Commands and Status"
order: 2
published: true
draft: false
---

# Commands and Status

Parent: [Server Administration](/mod/patchwork/server-administration) | [Home](/mod/patchwork/home)

Only Patchwork's elected active runtime registers commands. All commands require the `patchwork.admin` permission, which defaults to the `hytale:Admin` group.

## Commands

| Command | Use it for |
| --- | --- |
| `/patchwork status` | See the active/passive runtimes, roots, latest generation, inventory, skips, failures, and reload state. |
| `/patchwork reload` | Start one authorized, fresh generation and reload transaction. |
| `/patchwork conflicts` | List recent value-redacted conflict rows. |
| `/patchwork conflicts <exact-target>` | Limit conflict rows to one exact asset target. |
| `/patchwork selftest` | Run isolated Patchwork fixtures without touching production generated output. |

## Read Status in This Order

When something is wrong, start with `/patchwork status` and check:

1. **Active runtime** — confirm that the expected standalone or embedded copy won election.
2. **Roots** — confirm that the Patchwork neutral root is eligible and your patch is in the expected asset pack.
3. **Generation epoch and inventory** — look for the target you expected to generate.
4. **Skips and failures** — use the first related server-log message for the real reason.
5. **Reload category** — distinguish a generated change from a confirmed live change.

Status deliberately does not reveal condition data, expected values, document contents, rollback bytes, or runtime registration tokens. That makes it safe to share while still providing useful diagnostics.

## Use Self-Test Carefully

`/patchwork selftest` creates a UUID-named isolated run below Patchwork's self-test root. It exercises real generation with fixtures for operations, matching, cross-asset merges, target globs, conflict policy, and a mod-data condition. It does not alter production generated assets.

A successful standalone self-test can still report `restart-required`: it proves isolated generation and conditions, not that an arbitrary production asset was reloaded live.
