package org.jetbrains.koog.graph.checker.common

/**
 * Layer-agnostic model of a single Koog `strategy { }` / `subgraph { }` graph, built once per block
 * and reused by every structural check.
 *
 * The model is generic over an opaque *anchor* type `A`: the compiler layer instantiates it with
 * `KtSourceElement` and the IDE layer with `PsiElement`, so both build the same structure from their
 * own AST and then run the same shared analysis ([analyzeGraph]). Only the model building differs per
 * layer; the connectivity/duplicate/shadowing logic and the diagnostic text live here, shared.
 *
 * Each builder block is analyzed on its own (no recursion): a nested `subgraph { }` appears in its
 * parent as an opaque [NodeKind.SUBGRAPH] node, and is analyzed separately when the checker fires on
 * that nested `subgraph(...)` call. This keeps building flat and avoids double-reporting.
 */
class GraphModel<A>(
    /** The strategy/subgraph name (its first string argument), or null when unnamed. Used in messages. */
    val scopeName: String?,
    /** Anchor for whole-graph findings (e.g. finish-unreachable) — the `strategy`/`subgraph` callee. */
    val scopeAnchor: A,
    val nodes: List<GraphNode<A>>,
    val edges: List<GraphEdge<A>>,
)

enum class NodeKind {
    /** The implicit `nodeStart`. */
    START,

    /** The implicit `nodeFinish`. */
    FINISH,

    /** A `val x by node(...)` / `nodeLLMRequest(...)` / … declaration. */
    NODE,

    /** A `val x by subgraph(...) { }` declaration (treated as opaque at this level). */
    SUBGRAPH,
}

/**
 * A node in the graph.
 *
 * Edges connect nodes by [referenceName] — the `val`/property identifier used inside `forwardTo`
 * (`"nodeStart"` / `"nodeFinish"` for the implicit terminals). [effectiveName] is the routing/id name
 * Koog uses for uniqueness (an explicit string argument if present, otherwise the property name); the
 * two differ for e.g. `val first by nodeLLMRequest("call_llm")`. Connectivity checks key on
 * [referenceName]; duplicate-name detection keys on [effectiveName].
 */
class GraphNode<A>(
    val referenceName: String,
    val effectiveName: String,
    val kind: NodeKind,
    /** The domain classification of the node's emitted type, for exhaustiveness checking; null if unknown. */
    val emittedDomain: NodeDomain?,
    /** Where to report a node-level finding; null for the implicit start/finish. */
    val declarationAnchor: A?,
)

/**
 * An edge built from `edge(source forwardTo target …)` or the `source then target` infix operator.
 *
 * [conditional] is true when the builder chain contains any condition operator (`onCondition`,
 * `onIsInstance`, `onToolCalls`, …) — i.e. it may match-or-skip at runtime; a bare `forwardTo`
 * (optionally with `transformed`) is unconditional and always matches first.
 *
 * [condition] is a semantic classification of the edge's condition for the exhaustiveness check:
 * what the condition provably covers (a single enum entry, a type, a boolean literal, etc.),
 * whether it is a catch-all, or whether it is opaque (not statically analyzable). Unlike
 * [conditional] (which is a syntactic check for the shadowed-edge warning), this drives the
 * per-domain coverage computation.
 */
class GraphEdge<A>(
    val sourceName: String?,
    val targetName: String?,
    val conditional: Boolean,
    val condition: EdgeCondition,
    /** Anchor for an edge-level finding (e.g. a shadowed edge) — the `edge` callee / `then` operator. */
    val edgeAnchor: A,
    /** Anchor for the source-node reference (e.g. a `nodeFinish` with an illegal outgoing edge); null if unavailable. */
    val sourceAnchor: A?,
)

/**
 * What a single edge's condition provably covers, for the edge-condition exhaustiveness check.
 *
 * Only **pure discriminators** ([ValueMatch], [TypeCheck]) contribute to the covered set; [CatchAll]
 * immediately exhausts the domain; [Opaque] forces a bail-out (suppresses the warning for enumerable
 * domains, falls through to the weak "all conditional, no fallback" nudge for non-enumerable ones).
 */
sealed class EdgeCondition {
    /** Matches everything: bare `forwardTo` (no `on*` operators), or `onCondition { true }`. */
    data object CatchAll : EdgeCondition()

    /** Pure value discriminator: `onCondition { it == SomeEnum.ENTRY }` or `it == true/false`. */
    data class ValueMatch(val valueName: String) : EdgeCondition()

    /** Pure type discriminator: `onIsInstance(T::class)` with no further `onCondition`. */
    data class TypeCheck(val typeName: String) : EdgeCondition()

    /** Not statically analyzable — any other `on*` operator or complex condition body. */
    data object Opaque : EdgeCondition()
}

/**
 * The domain of values a node can emit, used to determine whether outgoing edges are exhaustive.
 *
 * Statically enumerable domains ([EnumDomain], [SealedDomain], [BooleanDomain]) allow exact
 * coverage checking; [NonEnumerable] can only trigger the weak "all conditional, no fallback"
 * nudge.
 */
sealed class NodeDomain {
    data class EnumDomain(val className: String, val entries: List<String>) : NodeDomain()
    data class SealedDomain(val className: String, val subtypes: List<String>) : NodeDomain()
    data object BooleanDomain : NodeDomain()
    data object NonEnumerable : NodeDomain()
}
