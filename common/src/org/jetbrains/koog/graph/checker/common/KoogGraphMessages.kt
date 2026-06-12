package org.jetbrains.koog.graph.checker.common

/**
 * The shared, fully-formatted message text for every structural graph check (spec §2.1–§2.6). Both
 * the compiler diagnostics and the IDE inspection build their messages here, so the wording stays
 * byte-identical across layers. Unlike the edge-type message these need no type rendering, so each
 * function returns a complete string that the compiler passes through a plain `{0}` diagnostic and
 * the IDE passes straight to `registerProblem`.
 */

/** §2.1 — an outgoing edge from the terminal node. */
fun finishOutgoingEdgeMessage(finishNode: String): String =
    "'$finishNode' cannot have outgoing edges — it is the terminal node of the graph."

/** §2.2 — no path of edges reaches the finish node. */
fun finishUnreachableMessage(scopeName: String?): String {
    val where = if (scopeName != null) " in \"$scopeName\"" else ""
    return "'nodeFinish' is not reachable from 'nodeStart'$where. The graph cannot terminate; " +
        "add an edge that eventually reaches 'nodeFinish'."
}

/** §2.3 — two nodes in the same graph share a name. */
fun duplicateNodeNameMessage(name: String, firstDeclaredAs: String): String =
    "Duplicate node name \"$name\" (already used by '$firstDeclaredAs'). " +
        "Node names must be unique within a strategy/subgraph."

/** §2.4 — a declared node nothing routes into. */
fun unreachableNodeMessage(node: String): String =
    "Node '$node' is never reached from 'nodeStart'; it will not execute. " +
        "Add an incoming edge (e.g. edge(... forwardTo $node))."

/** §2.5 — a conditional edge ordered after an unconditional edge from the same node. */
fun shadowedEdgeMessage(source: String): String =
    "This edge can never be taken: an earlier unconditional edge from '$source' always matches first. " +
        "Reorder so conditional edges precede the unconditional one."

/** §2.6 — a reachable, non-finish node with no outgoing edge. */
fun deadEndNodeMessage(node: String): String =
    "'$node' is reachable but has no outgoing edge; execution will stall here. " +
        "Add an edge from '$node' (e.g. to 'nodeFinish')."
