# Koog Graph Checker — Compiler Plugin

A Kotlin (K2/FIR) compiler plugin that emits clear, domain-specific diagnostics when a [Koog](https://github.com/jetbrains/koog) agent strategy graph is wired incorrectly.

## Problem

Koog strategy graphs are easy to wire incorrectly. Type mismatches between nodes produce Kotlin compiler errors with deeply nested generic signatures that don't explain the problem in domain terms. Structural mistakes — a missing edge to `nodeFinish`, an unreachable node, non-exhaustive routing — compile without errors but crash or stall at runtime.

## Solution

This plugin adds focused, readable diagnostics for common graph-wiring mistakes — from type mismatches on individual edges to structural problems like unreachable nodes, dead ends, and non-exhaustive routing. Many of these errors would otherwise surface only at runtime as `IllegalStateException` during strategy initialization, or as silent stalls during execution.

The user gets domain-level explanations that name the nodes involved and suggest fixes, alongside the standard Kotlin errors.

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

The plugin performs two categories of validation: **per-edge type checks** and **structural graph checks**. Many of these catch errors that Koog only reports at runtime (as `IllegalStateException` during strategy initialization), moving them to compile/edit time.

### Edge type mismatch

Validates that **the value type reaching the target node is a subtype of the target node's input type**. This covers direct `forwardTo` edges, edges with transforms (`transformed`, `onIsInstance`, etc.), and chains mixing type-neutral and type-changing operators. The diagnostic names both nodes and explains whether the mismatch comes from the source node's output or a transform result.

```kotlin
val source by node<String, Int> { it.length }
val target by node<String, String> { it }

edge(source forwardTo target)
// Invalid edge from 'source' to 'target': the source node's output type Int
//   does not match 'target' input type String.

edge(source forwardTo target transformed { it.toLong() })
// Invalid edge from 'source' to 'target': the value type after the transform Long
//   does not match 'target' input type String.
```

### Structural graph checks

These checks build a model of the entire `strategy { }` or `subgraph { }` block — collecting node declarations, `edge(...)` calls, and `then` operators — and validate structural invariants.

| Check | Severity | What it catches |
|-------|----------|-----------------|
| **Outgoing edge from `nodeFinish`** | Error | `edge(nodeFinish forwardTo ...)` — the finish node cannot have outgoing edges |
| **Finish node unreachable** | Error | No path of edges leads from `nodeStart` to `nodeFinish`; the graph can never terminate |
| **Duplicate node names** | Error | Two nodes share the same name (explicit or property-derived) within a strategy/subgraph |
| **Unreachable node** | Warning | A declared node has no path from `nodeStart` and will never execute |
| **Dead-end node** | Warning | A reachable non-finish node has no outgoing edges; execution will stall |
| **Shadowed edge** | Warning | A conditional edge is declared after an unconditional edge from the same node and can never be taken |
| **Non-exhaustive edge conditions** | Warning | A node routes on an enum, sealed class, or boolean, but not all cases are covered and there is no fallback edge |
| **All-conditional, no fallback** | Weak warning | All outgoing edges from a node are conditional with no catch-all; inputs matching no condition will stall |

### Exhaustiveness checks

When a node's output type is an **enum**, **sealed class/interface**, or **boolean**, the plugin checks that the outgoing edges cover all cases — the same idea as Kotlin's exhaustive `when`. It recognizes `onCondition { it == Entry }` for value matching and `onIsInstance(Type::class)` for type matching, and reports the specific missing cases. An unconditional fallback edge or `onCondition { true }` suppresses the check. Edges with opaque conditions (arbitrary lambdas) also suppress the check to avoid false alarms.

```kotlin
enum class Route { SEARCH, ANSWER, ESCALATE }

val classify by node<String, Route> { ... }
edge(classify forwardTo searchNode onCondition { it == Route.SEARCH })
edge(classify forwardTo answerNode onCondition { it == Route.ANSWER })
// 'classify' routes on enum Route but no edge handles: ESCALATE.
```

## Project structure

- **`:common`** — shared graph model, analysis logic, and diagnostic messages used by both the compiler and IDE plugins.
- **`:compiler-plugin`** — the K2/FIR checkers that detect and report graph issues during compilation.
- **`:gradle-plugin`** — a Gradle plugin that applies the compiler plugin to any Kotlin project.
- **`:ide-plugin`** — an IntelliJ IDEA plugin that surfaces the same diagnostics as live inspections, without requiring a compilation step.

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
