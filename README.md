# Koog Graph Checker — Compiler Plugin

A Kotlin (K2/FIR) compiler plugin that emits clear, domain-specific diagnostics when a [Koog](https://github.com/jetbrains/koog) agent strategy graph is wired incorrectly.

## Problem

When two nodes in a Koog strategy graph are connected by an edge whose types don't line up, the Kotlin compiler reports a generic type-mismatch involving deeply nested generic signatures such as:

```
AIAgentNodeBase<*, SomeLongType> vs AIAgentNodeBase<AnotherLongType, *>
```

These messages are hard to read and don't explain the problem in terms the user thinks about — *node A's output type doesn't match node B's input type*.

## Solution

This plugin adds a focused, readable diagnostic alongside the existing compiler error:

```
Invalid edge: the edge's output type Int does not match the target node's input type String.
```

The user gets both the standard Kotlin failure and the domain-level explanation.

## How Koog graphs are defined

```kotlin
val strategy = strategy<String, String>("name") {
    val source by node<String, Int> { input -> input.length }
    val target by node<Int, String> { input -> input.toString() }

    edge(nodeStart forwardTo source)
    edge(source forwardTo target)         // valid: source output Int == target input Int
    edge(target forwardTo nodeFinish)
}
```

- `node<Input, Output>(...)` declares a node with explicit input and output types.
- `nodeStart` / `nodeFinish` are the implicit entry/exit nodes; their types come from the enclosing `strategy<In, Out>`.
- `source forwardTo target` builds an edge. It is only valid when the output type of `source` is assignable to the input type of `target`.
- Operators such as `transformed { }` / `transform<T> { }` adapt the output type flowing toward the target. After a transform, the *transformed* output must still match the target's input.
- Type-neutral operators such as `onCondition { }` gate traversal without changing the flowing type.

## What the plugin checks

The plugin validates the single rule Koog's own `edge(...)` function enforces: **the value type reaching the target node must be a subtype of the target node's input type**. This covers:

- A direct `source forwardTo target` edge with incompatible types.
- An edge that applies one or more transforms (e.g. `transformed`, `onIsInstance`, `asUserMessage`, or any user-defined extension) where the final transformed type still doesn't match the target's input.
- Chains that mix type-neutral operators with type-changing ones in any order.

The check is driven entirely by the resolved types of the edge builder expression, so it works automatically for any operator — built-in or custom — without naming them explicitly.

### Example — type mismatch without a transform

```kotlin
val source by node<String, Int> { it.length }   // output: Int
val target by node<String, String> { it }        // input:  String

edge(source forwardTo target)
// KOOG_EDGE_TYPE_MISMATCH: Invalid edge: the edge's output type Int
//   does not match the target node's input type String.
```

### Example — type mismatch after a transform

```kotlin
val source by node<String, Int> { it.length }   // output: Int
val target by node<String, String> { it }        // input:  String

edge(source forwardTo target transformed { it.toLong() })
// KOOG_EDGE_TYPE_MISMATCH: Invalid edge: the edge's output type Long
//   does not match the target node's input type String.
```

### Example — valid edge (no diagnostic)

```kotlin
edge(source forwardTo target transformed { it.toString() })
// Fine: transformed output String matches target input String.
```

## Project structure

- **`:compiler-plugin`** — the K2/FIR checker that detects and reports edge type mismatches.
- **`:gradle-plugin`** — a Gradle plugin that applies the compiler plugin to any Kotlin project.
- **`:ide-plugin`** — an IntelliJ IDEA plugin that surfaces the same diagnostic as a live inspection, without requiring a compilation step.

## IDE Plugin

The `ide-plugin` module provides an IntelliJ IDEA inspection that mirrors the compiler plugin check. Errors appear inline in the editor as you type, using the Kotlin Analysis API (K2-compatible, requires IntelliJ IDEA 2025.1 or later).

### Build and install locally

```bash
cd ide-plugin
./gradlew buildPlugin
```

The distributable is produced at `ide-plugin/build/distributions/koog-graph-checker-ide-*.zip`.

Install it in IntelliJ IDEA via **Settings → Plugins → ⚙️ → Install Plugin from Disk…** and select the `.zip` file.

### Run in a sandbox IDE

To launch a sandboxed IntelliJ IDEA instance with the plugin pre-loaded:

```bash
cd ide-plugin
./gradlew runIde
```

## Tests

The [Kotlin compiler test framework](https://github.com/JetBrains/kotlin/blob/master/compiler/test-infrastructure/ReadMe.md) is set up for this project.
To create a new test, add a `.kt` file under `compiler-plugin/testData/diagnostics/`.
The generated JUnit 5 test classes update automatically when tests are next run, or can be updated manually with the `generateTests` Gradle task.

It is recommended to install the [Kotlin Compiler DevKit](https://github.com/JetBrains/kotlin-compiler-devkit) IntelliJ plugin, which is pre-configured in this repository.
