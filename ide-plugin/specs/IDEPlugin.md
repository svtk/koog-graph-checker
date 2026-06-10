# Koog Graph Checker — IDE Plugin Spec

## Goal

Provide the same `KOOG_EDGE_TYPE_MISMATCH` diagnostic that the compiler plugin emits, but as a live
IntelliJ IDEA inspection. Users see the red underline and hover message as they type, without
needing to run a full compilation.

The compiler plugin remains the authoritative check at build time. The IDE plugin is a
companion that surfaces the same errors interactively in the editor.

---

## Background: What the Compiler Plugin Checks

The compiler plugin (`KoogEdgeTypeMismatchChecker`) fires on every `edge(...)` call inside a Koog
`strategy { }` block. It checks whether the first argument — an
`AIAgentEdgeBuilderIntermediate<IncomingOutput, IntermediateOutput, OutgoingInput>` — satisfies
the constraint `IntermediateOutput : OutgoingInput`.

```kotlin
val strategy = strategy<String, String>("name") {
    val source by node<String, Int>   { it.length }
    val target by node<String, String> { it }

    edge(source forwardTo target)
    //   ^^^^^^^^^^^^^^^^^^^^^^^^^^^
    //   IntermediateOutput = Int, OutgoingInput = String → mismatch → ERROR
}
```

The check is **purely type-driven**: it works for any operator chain (`transformed`, `onCondition`,
`onIsInstance`, user extensions), because it reads the final resolved types off the builder type,
not the operator names.

Two type-recovery fallbacks exist for when the `edge(...)` call fails to resolve (type parameters
are left unsubstituted by the compiler):

- `IntermediateOutput` — recovered from the transformation lambda's return type
- `OutgoingInput` — recovered from `forwardTo`'s own type argument

---

## Implementation Approach

### Mechanism: `LocalInspectionTool`

Use `LocalInspectionTool` (not `Annotator`) for the following reasons:

| Feature | `Annotator` | `LocalInspectionTool` |
|---------|------------|----------------------|
| Per-element suppression (`@Suppress`) | No | Yes |
| Inspection settings UI / enable-disable | No | Yes |
| Batch "Inspect Code" runs | No | Yes |
| Quick fixes | Both support | Both support |
| Real-time in-editor highlighting | Yes | Yes (since 2024.1 parallel) |

The inspection should highlight the `edge` call-site identifier (mirroring the compiler plugin
which reports on `calleeReference.source`).

### Type Analysis: Kotlin Analysis API

The IDE plugin must use the **Kotlin Analysis API** (`analyze { }`) to resolve Kotlin types at the
PSI level. This is the stable, IDE-side counterpart to the compiler's FIR API.

Key API surface used:
- `KtCallExpression` — PSI node for `edge(...)` calls
- `analyze { expression.resolveToCall() }` — resolves the call to get argument types
- `KaSession.buildClassType()` / `KaType.isSubtypeOf()` — type compatibility
- `KaType.render(KaTypeRendererForSource.WITH_SHORT_NAMES)` — human-readable type strings

### Plugin Dependency on the Kotlin Plugin

The IDE plugin must declare `org.jetbrains.kotlin` as a plugin dependency to access PSI types
(`KtCallExpression`, `KtNameReferenceExpression`) and the Analysis API.

---

## Architecture

```
KoogEdgeTypeMismatchInspection          (LocalInspectionTool)
  └── buildVisitor()
        └── visits KtCallExpression where callee name == "edge"
              └── analyze {
                    1. Resolve argument to AIAgentEdgeBuilderIntermediate<_, M, T>
                    2. Extract IntermediateOutput (M) and OutgoingInput (T)
                    3. Apply fallback recovery if either is a TypeParameterType
                    4. Check M.isSubtypeOf(T)
                    5. If not: report on the "edge" reference with message
                  }
```

Type recovery in the IDE layer mirrors the compiler plugin:

| Slot | Primary source | Fallback source |
|------|---------------|----------------|
| `IntermediateOutput` | Builder type arg [1] | Lambda return type of the innermost transform argument |
| `OutgoingInput` | Builder type arg [2] | Type arg [0] of `forwardTo` call |

---

## File Layout

```
ide-plugin/
├── specs/
│   └── IDEPlugin.md                    ← this file
└── src/
    └── main/
        ├── kotlin/
        │   └── org/jetbrains/koog/graph/checker/ide/
        │       └── KoogEdgeTypeMismatchInspection.kt
        └── resources/
            └── META-INF/
                └── plugin.xml
```

The existing template classes (`MyBundle`, `MyProjectService`, `MyProjectActivity`,
`MyToolWindowFactory`) should be removed as they are boilerplate and not needed for this plugin.

---

## `plugin.xml` Changes

1. Update `<id>`, `<name>`, `<vendor>`, and `<description>` to reflect the real plugin.
2. Add dependency on the Kotlin plugin.
3. Register the inspection under the `com.intellij` extension namespace.
4. Remove template extensions (toolWindow, postStartupActivity).

```xml
<idea-plugin>
    <id>org.jetbrains.koog.graph.checker.ide</id>
    <name>Koog Graph Checker</name>
    <vendor>JetBrains</vendor>
    <description>Highlights Koog strategy edge type mismatches in the editor.</description>

    <depends>com.intellij.modules.platform</depends>
    <depends>org.jetbrains.kotlin</depends>

    <extensions defaultExtensionNs="com.intellij">
        <localInspection
            language="kotlin"
            groupPath="Kotlin"
            groupName="Koog"
            displayName="Koog edge type mismatch"
            enabledByDefault="true"
            level="ERROR"
            implementationClass="org.jetbrains.koog.graph.checker.ide.KoogEdgeTypeMismatchInspection"/>
    </extensions>
</idea-plugin>
```

---

## `build.gradle.kts` Changes

Add `org.jetbrains.kotlin` plugin dependency so the Analysis API jars are on the classpath:

```kotlin
intellijPlatform {
    intellijIdea("2025.2.6.2")
    testFramework(TestFrameworkType.Platform)
    bundledPlugin("org.jetbrains.kotlin")   // ← add this
}
```

---

## `KoogEdgeTypeMismatchInspection` — Implementation Sketch

```kotlin
package org.jetbrains.koog.graph.checker.ide

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

class KoogEdgeTypeMismatchInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : KtVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val calleeText = expression.calleeExpression?.text ?: return
                if (calleeText != "edge") return

                analyze(expression) {
                    val argument = expression.valueArguments.firstOrNull()
                        ?.getArgumentExpression() ?: return@analyze
                    val builderType = argument.expressionType as? KaClassType ?: return@analyze
                    if (builderType.classId != EDGE_BUILDER_CLASS_ID) return@analyze

                    val typeArgs = builderType.typeArguments
                    val intermediateOutput = typeArgs.getOrNull(1)?.type
                        ?.takeUnless { it is KaTypeParameterType }
                        ?: /* fallback: lambda return type */ return@analyze
                    val outgoingInput = typeArgs.getOrNull(2)?.type
                        ?.takeUnless { it is KaTypeParameterType }
                        ?: /* fallback: forwardTo type arg */ return@analyze

                    if (intermediateOutput.isSubtypeOf(outgoingInput)) return@analyze

                    val message = "Invalid edge: the edge's output type " +
                        "${intermediateOutput.render()} does not match " +
                        "the target node's input type ${outgoingInput.render()}."

                    holder.registerProblem(
                        expression.calleeExpression ?: expression,
                        message,
                    )
                }
            }
        }

    companion object {
        private val EDGE_BUILDER_CLASS_ID = ClassId(
            FqName("ai.koog.agents.core.dsl.builder"),
            Name.identifier("AIAgentEdgeBuilderIntermediate"),
        )
    }
}
```

> **Note:** The fallback recovery for `IntermediateOutput` (from the lambda return type) and
> `OutgoingInput` (from `forwardTo`'s type argument) must be ported from the compiler plugin's
> logic. The sketch above shows the happy-path only and leaves those as `return@analyze` stubs.

---

## Testing Plan

Use `LightCodeInsightFixtureTestCase` (or the IntelliJ Platform test fixtures) with Kotlin plugin
on classpath to write inspection tests:

```kotlin
class KoogEdgeTypeMismatchInspectionTest : LightCodeInsightFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KoogEdgeTypeMismatchInspection())
    }

    fun testDirectMismatch() {
        myFixture.configureByText("test.kt", """
            // ... Koog strategy with Int→String mismatch
        """)
        myFixture.checkHighlighting()  // expects <error> markers in the code
    }
}
```

Test data files mirror the compiler plugin's `testData/diagnostics/` cases:

| Test file | Expected outcome |
|-----------|-----------------|
| `koogStrategy.kt` | No error |
| `koogStrategyEdgeTypeMismatch.kt` | Error on `edge` |
| `koogStrategyTransformed.kt` | Error on `edge` |
| `koogStrategyValidTransform.kt` | No error |
| `koogStrategyOnIsInstance.kt` | Error on `edge` |
| `koogStrategyOnConditionTransformed.kt` | Error on `edge` |

---

## Implementation Plan

### Step 1 — Clean up template boilerplate
- Delete `MyBundle`, `MyProjectService`, `MyProjectActivity`, `MyToolWindowFactory`
- Remove their registrations from `plugin.xml`
- Delete `messages/MyBundle.properties`

### Step 2 — Update `plugin.xml`
- Set real plugin id, name, vendor, description
- Add `<depends>org.jetbrains.kotlin</depends>`
- Register the `<localInspection>` extension point

### Step 3 — Add Kotlin bundled plugin to `build.gradle.kts`
- Add `bundledPlugin("org.jetbrains.kotlin")` inside the `intellijPlatform { }` dependencies block

### Step 4 — Implement `KoogEdgeTypeMismatchInspection`
- Create `src/main/kotlin/org/jetbrains/koog/graph/checker/ide/KoogEdgeTypeMismatchInspection.kt`
- Implement `buildVisitor()` visiting `KtCallExpression` nodes named `edge`
- Inside `analyze { }`:
  - Resolve the argument's type to `AIAgentEdgeBuilderIntermediate`
  - Extract type arguments [1] and [2]
  - Implement fallback recovery for unresolved type parameters (mirroring the compiler plugin)
  - Call `isSubtypeOf()` and register a problem if not satisfied

### Step 5 — Write tests
- Create `KoogEdgeTypeMismatchInspectionTest` extending `LightCodeInsightFixtureTestCase`
- Add test data files (reuse/adapt from `compiler-plugin/testData/diagnostics/`)
- Verify highlighting matches compiler plugin behaviour

### Step 6 — Manual verification
- Run the plugin in a sandboxed IDE instance (`./gradlew :ide-plugin:runIde`)
- Open a project using Koog with a graph that has edge type errors
- Confirm errors appear live as the user types, without recompilation
