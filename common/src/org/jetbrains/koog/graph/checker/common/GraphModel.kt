package org.jetbrains.koog.graph.checker.common

/**
 * Layer-agnostic model of a single Koog `strategy { }` / `subgraph { }` graph, built once per block
 * and reused by every structural check (spec §2.1–§2.6).
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
 * two differ for e.g. `val first by nodeLLMRequest("call_llm")`. Connectivity (§2.2/§2.4/§2.6) keys on
 * [referenceName]; duplicate detection (§2.3) keys on [effectiveName].
 */
class GraphNode<A>(
    val referenceName: String,
    val effectiveName: String,
    val kind: NodeKind,
    /** Where to report a node-level finding; null for the implicit start/finish. */
    val declarationAnchor: A?,
)

/**
 * An edge built from `edge(source forwardTo target …)` or the `source then target` infix operator.
 *
 * [conditional] is true when the builder chain contains any condition operator (`onCondition`,
 * `onIsInstance`, `onToolCalls`, …) — i.e. it may match-or-skip at runtime; a bare `forwardTo`
 * (optionally with `transformed`) is unconditional and always matches first (spec §2.5).
 */
class GraphEdge<A>(
    val sourceName: String?,
    val targetName: String?,
    val conditional: Boolean,
    /** Anchor for an edge-level finding (e.g. a shadowed edge) — the whole `edge(...)` call. */
    val edgeAnchor: A,
    /** Anchor for the source-node reference (e.g. the `nodeFinish` in §2.1); null if unavailable. */
    val sourceAnchor: A?,
)
