# Koog Graph Checker — Improvement Findings

Analysis of the current plugin against the Koog `1.0.0` strategy-graph API, with concrete,
grounded recommendations for clearer diagnostics and additional graph validation.

All "Koog grounding" references below point at the `ai.koog:koog-agents:1.0.0` sources
(`agents-core`), inspected for this report. The repo currently pins `koog = "1.0.0"`
(`gradle/libs.versions.toml`).

---

## 0. What the plugin checks today (baseline)

The plugin implements **exactly one** check, in two mirrored layers:

| Layer | Entry point | Mechanism |
|-------|-------------|-----------|
| Compiler (K2/FIR) | `KoogEdgeTypeMismatchChecker` (`compiler-plugin/.../fir/`) | `FirExpressionChecker<FirFunctionCall>` firing on every call named `edge` |
| IDE | `KoogEdgeTypeMismatchInspection` (`ide-plugin/.../ide/`) | `LocalInspectionTool` visiting `KtCallExpression` named `edge`, via the Analysis API |

**The rule:** for an `edge(...)` whose argument resolves to
`AIAgentEdgeBuilderIntermediate<IncomingOutput, IntermediateOutput, OutgoingInput>`, verify
`IntermediateOutput : OutgoingInput` (the value reaching the target, after any operators, must be a
subtype of the target node's input). This mirrors Koog's own `edge` bound
`CompatibleOutput : OutgoingInput` (`AIAgentSubgraphBuilderBase.edge`).

**Current message** (`KoogDiagnostics.kt` / inspection):

```
Invalid edge: the edge’s output type Int does not match the target node’s input type String.
```

Reported on the `edge` callee identifier. The check is purely type-driven, so it transparently
covers `transformed`, `onIsInstance`, `onToolCalls`, `asUserMessage`, and user-defined operators.

**Key observation:** because the check is *per-edge and type-only*, it already covers the whole
class of "forgot to transform the value" mistakes (e.g. wiring `nodeLLMRequest`, output
`Message.Assistant`, straight into a `String` node without `onAssistantMessage`/`onTextMessage`).
What it does **not** cover is anything **structural** — reachability, dead ends, duplicate names,
edges out of the finish node, and edge ordering. That is where the highest-value additions lie
(Section 2).

---

## 1. Error-reporting improvements (the existing check)

The diagnostic is correct but can be made clearer, better localized, and actionable. None of these
requires new graph analysis — only richer use of information already present at the call site.

### 1.1 Name the nodes, not just the types — HIGH value, LOW complexity

**Problem.** Users think in terms of *named nodes* ("`classify` feeds `summarize`"), but the message
only mentions types. When a strategy has several `Int`→`String` edges, the message does not say
*which* edge is wrong; the user must rely on the underline location alone.

**User impact.** Slower diagnosis in non-trivial graphs; the message is not self-contained in build
logs (where there is no editor underline).

**Implementation.** Both layers already have the builder expression in hand. The source/target node
names are recoverable from the `forwardTo` operands:
- Compiler: the `edge` argument is a `FirFunctionCall` chain; walk to the innermost `forwardTo` call
  and read its explicit receiver (`source`) and value argument (`target`) — both are
  `FirPropertyAccessExpression`s whose `calleeReference.name` is the node's `val` name.
- IDE: from the argument `KtExpression`, descend to the `forwardTo` `KtBinaryExpression`/call and read
  the left/right `KtNameReferenceExpression` text.

Node names are the property names by default (Koog uses `property.name` when no explicit name is
given — see `AIAgentNodeDelegate`/`AIAgentSubgraphDelegate.getValue`), so the `val` identifier is the
right thing to show. Fall back to the type-only message when a name cannot be recovered (e.g.
`nodeStart`/`nodeFinish`, or a non-trivial receiver expression).

**Example diagnostic (improved):**

```
Invalid edge from node 'classify' to node 'summarize': the value reaching 'summarize'
has type Message.Assistant, but 'summarize' accepts String.
```

### 1.2 Localize the highlight to the operand at fault — MEDIUM value, MEDIUM complexity

**Problem.** Today the underline is on the `edge` keyword. For a transform chain the actual fault is
the *transform result* or the *target node*, not the call as a whole.

**User impact.** For chains like `source forwardTo target onCondition { … } transformed { … }`, the
red `edge` doesn't point at the transform that produced the wrong type.

**Implementation.** Choose the report anchor by where the offending type originates:
- Bare `forwardTo` mismatch → underline the **target node reference** (it is the constraint that
  fails).
- Mismatch after a `transformed { … }` / operator → underline the **last operator expression**
  (the selector of the outermost `KtDotQualifiedExpression`), since that operator fixed the final
  `IntermediateOutput`.

This is the same information the checker already uses for type recovery (lambda result type vs
`forwardTo` type argument), so the anchor can be derived from the same branch.

**Example diagnostic (improved, anchored on the transform):**

```kotlin
edge(source forwardTo target transformed { it.toLong() })
//                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
// After 'transformed', the value type Long does not match 'target' input type String.
```

### 1.3 Make the message actionable / add an IDE quick fix — HIGH value, MEDIUM complexity

**Problem.** The message states the mismatch but not the remedy.

**User impact.** Users — especially those new to the DSL — know *that* the types disagree but not the
idiomatic fix (insert a `transformed { }`, or use `onAssistantMessage`/`onIsInstance` when the source
is a `Message`/`Any`).

**Implementation.**
- Message text: append a hint, e.g. *"Insert a `transformed { }` that converts Long to String, or
  change the target node's input type."*
- IDE only: register a `LocalQuickFix` on the inspection that inserts a `transformed { /* TODO: Long
  -> String */ }` stub before the closing paren, or — when `IntermediateOutput` is a supertype such
  as `Any`/`Message` — offers `onIsInstance(String::class)`. `LocalInspectionTool` already supports
  quick fixes (the IDE spec notes this as a reason to prefer it over `Annotator`).

**Example diagnostic (improved):**

```
Invalid edge from 'fetch' to 'parse': output type Long does not match input type String.
Insert `transformed { … }` to convert Long → String, or adjust 'parse' to accept Long.
```

### 1.4 Clarify "the edge’s output type" for transform chains — LOW value, LOW complexity

**Problem.** "the edge’s output type {0}" is ambiguous when an operator changed the type — `{0}` is the
*post-transform* type, but the wording suggests the source node's output.

**Implementation.** When recovery came from the transform branch, phrase as *"the value type after
the transform"*; for a bare `forwardTo`, keep *"the source node's output type"*. Cheap wording change
that removes a real source of confusion.

### 1.5 Minor: apostrophe / wording consistency — LOW value, trivial

The compiler message uses a typographic apostrophe (`edge’s`, `node’s`) while the IDE message uses a
straight apostrophe (`edge's`). Unify them so the compiler and IDE messages are byte-identical (the
project's stated goal is that the IDE "surfaces the same diagnostic"). Pick one form and share the
literal.

---

## 2. Additional graph validations (prioritized)

These are **structural** checks the plugin does not do today. The most valuable ones mirror
invariants **Koog enforces only at runtime** — i.e. the user's program compiles, then throws an
`IllegalStateException` the moment the `strategy { … }` initializer runs (the DSL builds eagerly:
`strategy(...) = AIAgentGraphStrategyBuilder(...).apply(init).build()`). Catching these statically
moves the failure from runtime to compile/edit time.

### Priority summary

| # | Check | User value | Impl. complexity | Koog enforces at runtime? |
|---|-------|-----------|------------------|---------------------------|
| 2.1 | Outgoing edge from `nodeFinish` | High | Low | Yes — `IllegalStateException` |
| 2.2 | Finish node unreachable / dead-end graph | High | High | Yes — `IllegalStateException` |
| 2.3 | Duplicate node names | Medium–High | Medium | Yes — uniqueness tracked / throws |
| 2.4 | Unreachable node (no path from `nodeStart`) | Medium–High | High | No (silent dead code) |
| 2.5 | Shadowed edge (unconditional edge ordered before others) | Medium | Medium | No (silent; first-match-wins) |
| 2.6 | Node with no outgoing edge (non-finish dead end) | Medium | Medium | No (runtime stall) |
| 2.7 | All-conditional fan-out with no fallback | Low–Medium | High | No (possible runtime stall) |
| 2.8 | Enum-routed fan-out: missing enum cases | Medium–High | Medium | No (silent stall on uncovered entry) |
| 2.9 | **Generalized edge-condition exhaustiveness** (subsumes 2.7 + 2.8) | Medium–High | Medium–High | No (silent stall on uncovered case) |

> **Architectural note (applies to all of 2.x).** Unlike the existing per-`edge` check, these need a
> view of the **whole `strategy { }` / `subgraph { }` block**: collect node declarations
> (`val x by node<…>`, `by subgraph<…>`, and the predefined `nodeLLMRequest`/`nodeExecuteTools`/… from
> `AIAgentNodes.kt`), all `edge(...)` calls, **and** the `then` infix operator (which also builds an
> edge: `AIAgentSubgraphBuilderBase.then` calls `edge(this forwardTo nextNode)`), plus the implicit
> `nodeStart`/`nodeFinish`. Recommended: a checker that fires once on the `strategy`/`subgraph` call
> and walks its trailing-lambda body, building an in-memory `Map<nodeName, List<edge targets>>`.
> Recurse into nested `subgraph { }` blocks (each has its own start/finish). The IDE layer builds the
> same model from PSI. This shared "graph model" component should be built once and reused by 2.2–2.8.

---

### 2.1 Outgoing edge from `nodeFinish`

**Problem.** `edge(nodeFinish forwardTo x …)` is illegal: the finish node cannot have outgoing edges.

**Koog grounding.** `FinishNode.addEdge` unconditionally throws
`IllegalStateException("FinishNode cannot have outgoing edges")` (`AIAgentNode.kt`). `addEdge` runs
while the `edge(...)` call executes inside the builder, so this throws as soon as the strategy
initializer runs.

**User impact.** A hard runtime crash with a message that doesn't point at a source location. Common
when copy-pasting edges or mixing up `nodeStart`/`nodeFinish`.

**Implementation.** Lowest-complexity structural check — it is effectively per-edge. On each
`edge(...)`, resolve the `forwardTo` **receiver**; if its type is `FinishNode<*>`
(`ai.koog.agents.core.agent.entity.FinishNode`), or it is the well-known `nodeFinish` reference,
report. No whole-graph model required, so this can ship before 2.2.

**Example diagnostic:**

```kotlin
edge(nodeFinish forwardTo nodeStart)
//   ~~~~~~~~~~
// 'nodeFinish' cannot have outgoing edges — it is the terminal node of the graph.
```

### 2.2 Finish node unreachable / dead-end graph

**Problem.** No path of edges leads from `nodeStart` to `nodeFinish`. The graph can never terminate.

**Koog grounding.** This is *exactly* the invariant Koog checks at build time:
`AIAgentSubgraphBuilderBase.buildSubgraphMetadata` calls `isFinishReachable(start)` and throws
`IllegalStateException("Finish node is not reachable from the start node in the subgraph '<name>'.")`;
`AIAgentSubgraphBuilder.build` similarly does `require(isFinishReachable(nodeStart)) { "FinishSubgraphNode
can't be reached from the StartNode of the agent's graph. …" }`. The plugin can reproduce
`isFinishReachable` statically — it is a plain reachability DFS over `node.edges`.

**User impact.** Highest-value structural check. Today the user writes a strategy, it compiles, and it
blows up the instant the `val strategy = strategy(...) { … }` initializer is evaluated (class init /
first use) — often far from where the wiring is wrong, and with no line number. Forgetting
`edge(lastNode forwardTo nodeFinish)` is an extremely common mistake (note that in the docs' Chess and
assistant examples, the finish edge is always explicit and conditional, e.g.
`edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })` — easy to omit).

**Implementation.** Build the graph model (see architectural note), run a DFS from `nodeStart`
following all edge targets, report if `nodeFinish` is not reached. Report on the `strategy`/`subgraph`
callee or its name argument. Mirror Koog's DFS so results match runtime exactly.

**Example diagnostic:**

```kotlin
val strategy = strategy<String, String>("pipeline") {   // ← reported here
    val a by node<String, Int> { it.length }
    val b by node<Int, String> { it.toString() }
    edge(nodeStart forwardTo a)
    edge(a forwardTo b)
    // forgot: edge(b forwardTo nodeFinish)
}
// 'nodeFinish' is not reachable from 'nodeStart' in strategy "pipeline".
// The graph cannot terminate. Add an edge that eventually reaches 'nodeFinish'
// (e.g. edge(b forwardTo nodeFinish)).
```

### 2.3 Duplicate node names

**Problem.** Two nodes in the same (sub)graph share a name. Names default to the `val` name but can be
overridden via `node("explicit")`, `nodeLLMRequest("call_llm")`, etc., so collisions arise when two
`val`s pass the same explicit name, or an explicit name shadows a property name.

**Koog grounding.** `AIAgentSubgraphBuilderBase.buildSubGraphNodesMap` throws
`IllegalStateException("Node with name '<name>' already exists in the subgraph.")`, and
`buildSubgraphMetadata` records `uniqueNames = names.toSet().size == names.size`. Names are used as
node `id` (`AIAgentNodeBase.id get() = name`) and for routing/observability, so collisions are
genuinely harmful.

**User impact.** Runtime crash or silent mis-identification of nodes in traces/checkpoints. Easy to
hit with copy-paste (`nodeLLMRequest("call_llm")` twice).

**Implementation.** From the graph model, compute each node's effective name (explicit string argument
if present, else the `val`/property name) and report any duplicates within the same block. Anchor on
the second declaration. Medium complexity: needs the declaration scan but no traversal.

**Example diagnostic:**

```kotlin
val first  by nodeLLMRequest("call_llm")
val second by nodeLLMRequest("call_llm")   // ← reported here
// Duplicate node name "call_llm" (already used by 'first'). Node names must be unique
// within a strategy/subgraph.
```

### 2.4 Unreachable node (declared but never reached from `nodeStart`)

**Problem.** A node is declared (and possibly has outgoing edges) but nothing routes into it from
`nodeStart`, so it is dead code.

**Koog grounding.** Koog only validates *finish* reachability, not per-node reachability, so this is
**silently** wrong — the node simply never executes. The reachability machinery
(`isFinishReachable`/`buildSubGraphNodesMap` DFS over `node.edges`) shows the traversal model to reuse.

**User impact.** A whole branch of intended logic never runs, with no error anywhere. Typically a
typo'd or missing incoming `edge(...)`.

**Implementation.** Reuse the DFS from 2.2: any declared node not in the visited set (excluding
`nodeStart`) is unreachable. Report as a **warning** (not error — it is legal Kotlin and may be
intentional during development). Anchor on the node declaration.

**Example diagnostic:**

```kotlin
val orphan by node<String, String> { it }   // ← warning
// Node 'orphan' is never reached from 'nodeStart'; it will not execute.
// Add an incoming edge (e.g. edge(... forwardTo orphan)).
```

### 2.5 Shadowed edge (unconditional edge ordered before later edges)

**Problem.** When a node has multiple outgoing edges, Koog tries them **in declaration order** and
takes the first that matches. A bare `forwardTo` (no condition) *always* matches, so any edge declared
**after** an unconditional edge from the same node is unreachable.

**Koog grounding.** `AIAgentNodeBase.resolveEdge` iterates `edges` in order and returns the first whose
`forwardOutputUnsafe` is non-empty (`AIAgentNode.kt`). A plain `forwardTo` builds
`forwardOutputComposition = { _, output -> Some(output) }` (`AIAgentNodeBase.forwardTo`) — i.e. never
empty — while every condition operator (`onCondition`, `onToolCalls`, `onIsInstance`, …) wraps the
composition in a `filter`/type-check that can yield empty (`AIAgentEdgeBuilderIntermediate`,
`AIAgentEdges.kt`). So "unconditional first" provably shadows the rest.

**User impact.** Conditional routing silently never fires; the agent always takes the unconditional
branch. Hard to debug because the code "looks" like it branches.

**Implementation.** In the graph model, group edges by source node in declaration order. An edge is
"unconditional" if its builder chain contains **no** condition operator — detectable because its final
`IntermediateOutput` equals the source node's output type *and* no `onX`/`onCondition` selector appears
in the chain (simplest: flag the edge whose argument is a bare `forwardTo` call with no further
`onCondition`/`on*` selector). Report any edge declared after an unconditional edge from the same node.

**Example diagnostic:**

```kotlin
edge(llm forwardTo finish)                          // unconditional — always taken
edge(llm forwardTo tools onToolCalls { true })      // ← warning: unreachable
// This edge can never be taken: an earlier unconditional edge from 'llm'
// always matches first. Reorder so conditional edges precede the unconditional one.
```

### 2.6 Non-finish node with no outgoing edge (structural dead end)

**Problem.** A reachable, non-`finish` node has zero outgoing edges. When execution reaches it,
`resolveEdge` returns `null` and the run cannot continue.

**Koog grounding.** `resolveEdge` returns `null` when no edge matches (`AIAgentNode.kt`); a node with
`edges == emptyList()` always returns `null`. Only `FinishNode` is a legitimate terminal.

**User impact.** Runtime stall/termination once that node is hit — and since it may be hit only on a
particular branch, it can lurk until a specific input triggers it.

**Implementation.** From the graph model: for every reachable node that is not `nodeFinish` and not a
`subgraph` whose own finish is wired, check `outgoing.isEmpty()`. Report. (Combine with 2.4's
traversal.) Warning-level, since the node might be a work-in-progress.

**Example diagnostic:**

```kotlin
val process by node<String, String> { it }
edge(nodeStart forwardTo process)
// 'process' is reachable but has no outgoing edge; execution will stall here.
// Add an edge from 'process' (e.g. to 'nodeFinish').
```

### 2.7 All-conditional fan-out with no fallback

**Problem.** A node's outgoing edges are *all* conditional. If, at runtime, none of the conditions
match the node's output, `resolveEdge` returns `null` and the run stalls — there is no catch-all.

**Koog grounding.** Same `resolveEdge` first-match semantics; conditions can all yield empty. The docs'
patterns almost always pair a conditional edge with a fallback (e.g. Chess example:
`onToolCall { true }` *and* `onAssistantMessage { true }` cover both LLM outcomes), which is precisely
the safety property worth nudging toward.

**User impact.** Intermittent runtime stalls on inputs the author didn't anticipate.

**Implementation.** Lower confidence — exhaustiveness of arbitrary boolean lambdas is undecidable, so
this must be a **weak warning** (and probably off by default / opt-in). Heuristic: a node where every
outgoing edge carries a condition operator and none is a bare `forwardTo`. Suppress when conditions are
obviously total (e.g. `onCondition { true }`). Because of false-positive risk, rank this last.

**Example diagnostic (weak warning):**

```kotlin
edge(llm forwardTo a onCondition { it.length > 10 })
edge(llm forwardTo b onCondition { it.length <= 5 })
// 'llm' has only conditional outgoing edges; inputs matching no condition (length 6–10)
// will stall. Consider an unconditional fallback edge.
```

### 2.8 Enum-routed fan-out: missing enum cases

**Problem.** A node emits an **enum** value and its outgoing edges route on that value by comparing it
to enum entries (`onCondition { it == Status.APPROVED }`, …). If the edges don't cover every entry of
the enum and there is no fallback, the uncovered entries have no matching edge.

This is the **decidable special case of 2.7**: unlike arbitrary boolean lambdas, equality against enum
entries is statically enumerable, so the plugin can compute exactly which entries are missing — exactly
as Kotlin already does for `when` over an enum. That makes it high-confidence (very low false-positive
risk) and therefore worth shipping as a real warning, where 2.7 can only be a heuristic.

**User impact.** When the node later produces an uncovered entry, `resolveEdge` finds no matching edge,
returns `null`, and the run stalls — a routing bug that surfaces only on the input that yields the
missing case. Adding a new entry to the enum later silently reintroduces the gap (the same footgun a
non-exhaustive `when` has, but with no compiler error here today).

**Koog grounding.** Routing is first-match over `AIAgentNodeBase.resolveEdge`; a condition operator
adds a `filter` that yields empty when it doesn't match (`AIAgentEdgeBuilderIntermediate.onConditionBlocking`,
and `onCondition` in `AIAgentEdges.kt`). The value the condition inspects is the edge's
`IntermediateOutput` — the same type the existing checker already reads off the builder type. So the
"is this an enum?" test reuses machinery the plugin already has; only the enum-entry enumeration and the
lambda-body scan are new.

**Implementation.**
1. Group outgoing edges by source node (the shared graph model from 2.5/2.7).
2. Take the source node's emitted type (`IntermediateOutput` before any type-changing transform). If
   its class symbol is not an `enum class`, skip — this check does nothing for non-enum fan-outs.
   - FIR: `coneType.toRegularClassSymbol(session)?.classKind == ClassKind.ENUM_CLASS`; entries via the
     class's enum-entry symbols.
   - IDE: `KaType` → `expandedSymbol` as `KaNamedClassSymbol` with `classKind == ENUM_CLASS`; entries
     via its enum-entry members.
3. For each edge, scan the `onCondition` lambda body for the **high-confidence pattern only**: a
   comparison `it == Enum.ENTRY` / `param == Enum.ENTRY` (either operand order), or a `when`/`in`
   over enum entries. Collect the referenced entries.
4. If **any** edge from the node is unconditional (bare `forwardTo`) or an obvious catch-all
   (`onCondition { true }`, or an `else`-covering branch), the fan-out is total → **suppress**.
5. Otherwise compare covered entries against the full entry set; report the **missing** ones.
6. **Bail out (no warning) the moment a condition is not statically understood** — a comparison against
   a non-constant, a negation, a complex boolean, etc. Coverage is then unknown, and silence is correct:
   this check must never fire when it cannot prove a gap. (That bail-out is exactly what keeps it
   distinct from the heuristic 2.7.)

Anchor the report on the source node reference (or the `strategy`/`subgraph` callee), and ideally list
the missing entries. An IDE quick fix can insert a stub edge per missing entry.

**Example diagnostic:**

```kotlin
enum class Route { SEARCH, ANSWER, ESCALATE }

val classify by node<String, Route> { … }
edge(classify forwardTo searchNode   onCondition { it == Route.SEARCH })
edge(classify forwardTo answerNode   onCondition { it == Route.ANSWER })
// missing: Route.ESCALATE
// 'classify' routes on enum Route but no edge handles: ESCALATE.
// When 'classify' returns ESCALATE no edge matches and the run stalls.
// Add an edge for ESCALATE, or an unconditional fallback edge from 'classify'.
```

**Natural generalization (optional).** The same exhaustiveness idea extends to **sealed** hierarchies
routed by `onIsInstance(Subtype::class)`: enumerate the permitted subtypes of a sealed class/interface
and warn on any subtype with no matching `onIsInstance` edge and no fallback. Slightly more involved
(must read the sealed subclass list and reconcile `onIsInstance`'s reified type argument), but it shares
all of 2.8's scaffolding and covers the other idiomatic "route by case" pattern in Koog.

---

### 2.9 The general pattern: edge-condition exhaustiveness (how Kotlin does `when`, applied to edges)

2.7 (all-conditional fan-out) and 2.8 (missing enum cases) are two points on one spectrum. The
unifying observation is that **routing a node's output across conditional outgoing edges is the same
problem the Kotlin compiler already solves for `when` over that output's type** — and the compiler's
algorithm can be transplanted almost directly. This section describes that algorithm, the mapping onto
Koog edges, and the one design decision (a deliberate soundness-bias *flip*) that adapts it to a
linter.

#### How the Kotlin FIR compiler computes exhaustiveness

The FIR exhaustiveness checker (`FirWhenExhaustivenessComputer`, design notes in
`docs/fir/data-flow-based-exhaustiveness.md`) reuses the **smartcast data-flow machinery**. The state
it carries is a `TypeStatement` for the `when` subject `v`, of the extended form:

```
v : P1 & … & Pn  &  ¬(N1 | … | Nk | typeOf(val1) | … | typeOf(valm))
```

- the **positive** part (`Pi`) is the subject's known upper bound — its *domain*;
- the **negative** part is everything matched-and-excluded so far: excluded *types* (`Ni`, from `is`
  branches) and excluded individual *values* (`typeOf(valⱼ)`, from enum-entry / `true` / `false` /
  `null` branches).

Each branch adds its case to the negative set. The domain is **exhausted exactly when the accumulated
negatives contradict the positive** — `v : T & ¬T`. If, after the last branch, no contradiction is
reached, the residual `positive − negatives` *is* the set of uncovered cases, surfaced as
`WhenMissingCase`:

| `WhenMissingCase` | Domain it comes from | `branchConditionText` |
|---|---|---|
| `EnumCheckIsMissing(entry)` | `enum class` | the entry name |
| `IsTypeCheckIsMissing(type)` | `sealed` class/interface | `is Subtype` |
| `BooleanIsMissing(true/false)` | `Boolean` | `true` / `false` |
| `NullIsMissing` | nullable type | `null` |
| `Unknown` | **non-enumerable** domain (e.g. `Int`, `String`) | `else` |

A domain is **statically enumerable** precisely when its inhabitants reduce to a finite set of positive
types + `typeOf` values whose exclusion can produce that contradiction: enums (entries), sealed
hierarchies (permitted subtypes), `Boolean` (two literals), nullability (the `null` value), singleton
`object`s. Everything else is `Unknown` → only an `else` can exhaust it. The merge of parallel
data-flow paths is **biased toward soundness**: approximations may only *raise* the upper bound (`CST`)
or *weaken* the negatives, never tighten them unsoundly — so the compiler never falsely concludes
"exhaustive" (the price is that some genuinely-exhaustive but complex cases aren't recognized; see
`complementarySealedVariantsLimitations.kt`, and the deliberately non-exponential `getIntersectedLowerType`).

#### The mapping onto Koog edges

A node's fan-out is structurally a `when` whose subject is the node's emitted value:

| `when (subject) { … }` | Koog fan-out from node `N` |
|---|---|
| subject `v` | `N`'s emitted type = the edges' `IntermediateOutput` *before* type-changing transforms |
| `is T ->` branch | edge whose condition is a **pure** `onIsInstance(T::class)` |
| `value ->` branch | edge whose condition is a **pure** `onCondition { it == value }` (enum entry / `true` / `false` / `null`) |
| `else ->` branch | a **fallback** edge: bare `forwardTo`, or `onCondition { true }` |
| expression must be exhaustive | every reachable output value must match **some** edge, else `resolveEdge` returns `null` and the run stalls |

So the plugin can carry the very same `TypeStatement`-style state per source node — *positive* =
emitted domain, *negatives* = cases provably covered by the edges — and report the residual exactly as
`WhenMissingCase` does. 2.8 is the `EnumCheckIsMissing` instance; the §2.8 sealed generalization is the
`IsTypeCheckIsMissing` instance; the same code also yields `BooleanIsMissing`/`NullIsMissing` for free.

#### The one adaptation: flip the soundness bias

The compiler and the linter want opposite guarantees, and this dictates how *opaque* branches are
treated:

- **Kotlin** must never *miscompile*, so it is biased against falsely claiming **exhaustive**. A branch
  it cannot read as a clean type/value check (an arbitrary guard) simply **contributes nothing** to the
  negatives — coverage stays low, the `when` is treated as *not* exhaustive, and the user is asked for
  an `else`.
- **The plugin** must never *false-alarm*, so it is biased against falsely claiming a **gap**. Here an
  edge whose condition is not a clean discriminator must be treated as **possibly covering anything**
  (it might be the de-facto catch-all) → it **suppresses** the warning rather than counting as
  zero-coverage.

Concretely, only an edge that is a **pure discriminator** contributes a negative (provable coverage):

- `onIsInstance(T::class)` with no further `onCondition` — *Koog reduces it to exactly
  `onCondition { it is T }.transformed { it as T }`* (`AIAgentEdges.kt`), a clean type test → excludes `T`;
- `onCondition { it == E }` where `E` is an enum entry / `true` / `false` / `null` and the lambda is
  *only* that comparison → excludes that value;
- bare `forwardTo` or `onCondition { true }` → catch-all → exhausts immediately (suppress).

Everything else is **opaque** and forces a bail-out (no warning). This is important because Koog's
higher-level operators *look* like type partitions but are not: `onToolCalls { block }`,
`onToolCall(tool)`, `onSuccessful { … }`, `onFailure { … }` all wrap a type-narrowing in an **arbitrary
user `block`** (`AIAgentEdges.kt`), so even `onSuccessful` + `onFailure` over a `SafeTool.Result` is
*not* provably total — the inner predicate may reject. Counting them as full coverage would create
false "all cases covered" *or* false "missing case" reports; treating them as opaque is the safe choice.
The lone exception worth special-casing is the literal-`true` guard (`onToolCalls { true }`,
`onAssistantMessage { true }` — the canonical Chess pattern), which *is* total over its narrowed type.

#### Unified algorithm (one check, replacing 2.7 + 2.8)

1. Build the per-source-node edge grouping (shared graph model).
2. Determine the source node's emitted domain and **classify** it (reusing Kotlin's categories):
   enum / sealed / boolean / nullable / object / **non-enumerable**.
3. Walk the edges in order, accumulating the covered (negative) set from **pure discriminators only**;
   a catch-all (`forwardTo` / `{ true }`) ⇒ exhaustive ⇒ stop, no report.
4. **If any edge is opaque**, or the domain is **non-enumerable**, you cannot prove a gap:
   - non-enumerable domain with **zero** catch-all ⇒ emit only the weak **2.7** nudge ("all conditional,
     no fallback");
   - otherwise ⇒ **suppress**.
5. If the domain is enumerable and every edge was a pure discriminator, compute the residual
   `domain − covered`. If non-empty, report it as the corresponding `WhenMissingCase` category
   (`EnumCheckIsMissing` / `IsTypeCheckIsMissing` / `BooleanIsMissing` / `NullIsMissing`).

This is precisely the FIR computation with the bias flipped at step 4. 2.8's enum path is step 5 with
the enum classification; 2.7 is the degenerate step-4 branch for domains that were never enumerable.

#### Example diagnostics across domains

```kotlin
// Boolean domain — BooleanIsMissing(false)
val decide by node<String, Boolean> { it.isNotEmpty() }
edge(decide forwardTo yes onCondition { it == true })
// 'decide' routes on Boolean but no edge handles: false.
// Add an edge for the 'false' case, or an unconditional fallback edge from 'decide'.

// Sealed domain (Koog's message hierarchy) — IsTypeCheckIsMissing(...)
val llm by nodeLLMRequest()                       // emits Message.Response (sealed)
edge(llm forwardTo runTools onToolCalls { true })  // covers the tool-call case only
// 'llm' routes on Message.Response but no edge handles the assistant-message case.
// Add e.g. edge(llm forwardTo nodeFinish onAssistantMessage { true }), or a fallback edge.
```

(The first is `BooleanIsMissing`, the second `IsTypeCheckIsMissing` — same engine, same per-node state,
different domain classification. An `Int`- or `String`-typed fan-out lands in step 4 and, at most, gets
the weak 2.7 nudge.)

#### Verdict

Worth building **as** the implementation of 2.7 + 2.8 rather than alongside them: a single
"condition-coverage" component, parameterized by domain classifier + pure-discriminator extractor,
delivers the high-confidence enum/sealed/boolean/null warnings *and* degrades gracefully to the weak
all-conditional nudge, with the bail-out rule guaranteeing no false alarms. It mirrors machinery
Koog users already understand from `when`, so the diagnostics read naturally ("no edge handles X" ≈
"`when` must be exhaustive, add a branch for X"). Complexity is Medium–High only because of the
condition-chain reader (recognizing pure `onIsInstance` / `== entry` / `{ true }` and rejecting
guarded operators); the coverage arithmetic itself is small.

---

## 3. Suggested roadmap

Ordered by value-to-effort, reusing one shared graph model for the structural checks:

1. **Quick wins on the existing diagnostic (Section 1.1–1.4):** name the nodes, anchor the highlight,
   add an actionable hint + IDE quick fix. No new analysis; large clarity gain.
2. **2.1 Outgoing edge from `nodeFinish`:** per-edge, ships independently, prevents a guaranteed crash.
3. **Build the shared block-level graph model** (nodes incl. predefined + `subgraph`; edges from
   `edge(...)` **and** `then`; recurse subgraphs).
4. **2.2 Finish-unreachable** + **2.4 unreachable node** + **2.6 dead-end node** — all fall out of one
   DFS over the model; together they mirror and extend Koog's own `isFinishReachable`.
5. **2.3 Duplicate names** — declaration scan over the same model.
6. **2.5 Shadowed edges** — edge-ordering analysis over the model.
7. **2.9 Edge-condition exhaustiveness** — build it as the single "condition-coverage" component
   described in §2.9 (domain classifier + pure-discriminator extractor + residual = `WhenMissingCase`).
   This *is* the implementation of 2.7 and 2.8: ship the high-confidence enumerable-domain warnings
   first (2.8 enum, then sealed/boolean/null — all the same code), with the opaque/non-enumerable
   bail-out built in from the start so there are no false alarms.
8. **2.7 All-conditional fan-out** — the degenerate non-enumerable branch of the §2.9 component; enable
   it last as an opt-in weak nudge, since it is the only path that can produce false positives.

Each structural check must be implemented **twice** (FIR checker + Analysis-API inspection) to keep the
compiler and IDE in lockstep, exactly as the existing edge-type check is, and should reuse the
`testData/diagnostics/` ⟷ `ide-plugin/.../testData/inspection/` paired-fixture pattern already in place.

---

## Appendix — Koog source references used

| Fact | Source (`agents-core` 1.0.0) |
|------|------------------------------|
| `edge` bound `CompatibleOutput : OutgoingInput` | `dsl/builder/AIAgentSubgraphBuilder.kt` → `AIAgentSubgraphBuilderBase.edge` |
| `then` builds an edge | same file → `AIAgentSubgraphBuilderBase.then` |
| `isFinishReachable` DFS + finish-reachability throw | same file → `isFinishReachable`, `buildSubgraphMetadata`, `AIAgentSubgraphBuilder.build` |
| Duplicate-name throw / `uniqueNames` | same file → `buildSubGraphNodesMap`, `buildSubgraphMetadata` |
| First-match edge resolution | `agent/entity/AIAgentNode.kt` → `AIAgentNodeBase.resolveEdge` |
| Bare `forwardTo` always matches (`Some(output)`) | same file → `AIAgentNodeBase.forwardTo` |
| `FinishNode` rejects outgoing edges | same file → `FinishNode.addEdge` |
| Conditions wrap composition in `filter`/type-check | `dsl/builder/AIAgentEdgeBuilderIntermediate.kt`, `dsl/extension/AIAgentEdges.kt` (`onCondition`, `onIsInstance`, `onToolCalls`, `onTextMessage`, …) |
| `onIsInstance(T::class)` ≡ `onCondition { it is T }.transformed { it as T }` (a clean type-test discriminator) | `dsl/extension/AIAgentEdges.kt` → `onIsInstance` |
| Higher-level operators narrow type **then** apply an arbitrary `block` (not provably total) | same file → `onToolCalls`, `onToolCall`, `onSuccessful`, `onFailure` |
| Node names default to property name | `dsl/builder/AIAgentNodeDelegate.kt`, `AIAgentSubgraphDelegate.getValue` |
| Predefined node output types (e.g. `nodeLLMRequest`: `Message.Assistant`, `nodeExecuteTools`: `ReceivedToolResults`) | `dsl/extension/AIAgentNodes.kt` |
| Eager build (`apply(init).build()`) | `dsl/builder/AIAgentGraphStrategyBuilder.kt` → `strategy(...)` |

### Kotlin compiler references used (for §2.9 `when`-exhaustiveness)

| Fact | Source (JetBrains/kotlin) |
|------|---------------------------|
| Data-flow `TypeStatement` model; contradiction ⇒ exhaustive; soundness-biased merges (`CST`, `getIntersectedLowerType`) | `docs/fir/data-flow-based-exhaustiveness.md` |
| Exhaustiveness computation entry point | `compiler/fir/resolve/.../transformers/FirWhenExhaustivenessComputer.kt`; checker `compiler/fir/checkers/.../expression/FirExhaustiveWhenChecker.kt` |
| Missing-case taxonomy (`EnumCheckIsMissing`, `IsTypeCheckIsMissing`, `BooleanIsMissing`, `NullIsMissing`, `Unknown`/`else`) | `compiler/frontend.common/.../diagnostics/WhenMissingCase.kt` |
| Recognized-exhaustive limitations (complex sealed/negative cases not proven) | `compiler/testData/.../complementarySealedVariantsLimitations.kt` |

