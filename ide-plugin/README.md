# Koog Graph Checker — IDE Plugin

An IntelliJ IDEA plugin that surfaces [Koog](https://github.com/jetbrains/koog) strategy graph edge type mismatches as live inspections in the editor, without requiring a compilation step.

It mirrors the diagnostic produced by the [koog-graph-checker compiler plugin](../README.md) using the Kotlin Analysis API, so errors appear inline as you type.

## Requirements

IntelliJ IDEA 2025.1 or later (K2 Kotlin mode, enabled by default).

## Installation

**From disk (local build):**

```bash
./gradlew buildPlugin
```

The distributable is produced at `build/distributions/koog-graph-checker-ide-*.zip`.

In IntelliJ IDEA: **Settings → Plugins → ⚙️ → Install Plugin from Disk…**, then select the `.zip`.

**Run in a sandbox IDE:**

```bash
./gradlew runIde
```

This downloads the configured IntelliJ IDEA version and launches it with the plugin already loaded.

## What it checks

The inspection fires on any `edge(...)` call where the value type flowing into the target node is not a subtype of the target node's input type. This covers direct edges, edges with transforms (`transformed { }`, `onIsInstance`, etc.), and chains mixing type-neutral and type-changing operators.

```
Invalid edge: the edge's output type Int does not match the target node's input type String.
```

See the [project README](../README.md) for full examples.
