package org.jetbrains.koog.graph.checker.common

/**
 * The shared, fully-formatted message text for every structural graph check. Both
 * the compiler diagnostics and the IDE inspection build their messages here, so the wording stays
 * byte-identical across layers. Unlike the edge-type message these need no type rendering, so each
 * function returns a complete string that the compiler passes through a plain `{0}` diagnostic and
 * the IDE passes straight to `registerProblem`.
 */

/** An outgoing edge from the terminal node. */
fun finishOutgoingEdgeMessage(finishNode: String): String =
    "'$finishNode' cannot have outgoing edges — it is the terminal node of the graph."

/** No path of edges reaches the finish node. */
fun finishUnreachableMessage(scopeName: String?): String {
    val where = if (scopeName != null) " in \"$scopeName\"" else ""
    return "'nodeFinish' is not reachable from 'nodeStart'$where. The graph cannot terminate; " +
        "add an edge that eventually reaches 'nodeFinish'."
}

/** Two nodes in the same graph share a name. */
fun duplicateNodeNameMessage(name: String, firstDeclaredAs: String): String =
    "Duplicate node name \"$name\" (already used by '$firstDeclaredAs'). " +
        "Node names must be unique within a strategy/subgraph."

/** A declared node nothing routes into. */
fun unreachableNodeMessage(node: String): String =
    "Node '$node' is never reached from 'nodeStart'; it will not execute. " +
        "Add an incoming edge (e.g. edge(... forwardTo $node))."

/** A conditional edge ordered after an unconditional edge from the same node. */
fun shadowedEdgeMessage(source: String): String =
    "This edge can never be taken: an earlier unconditional edge from '$source' always matches first. " +
        "Reorder so conditional edges precede the unconditional one."

/** A reachable, non-finish node with no outgoing edge. */
fun deadEndNodeMessage(node: String): String =
    "'$node' is reachable but has no outgoing edge; execution will stall here. " +
        "Add an edge from '$node' (e.g. to 'nodeFinish')."

/** All outgoing edges from a node are conditional with no unconditional fallback. */
fun allConditionalNoFallbackMessage(node: String): String =
    "'$node' has only conditional outgoing edges; inputs matching no condition will stall. " +
        "Consider adding an unconditional fallback edge from '$node'."

/** An enumerable domain (enum/sealed/boolean) is not fully covered by edge conditions. */
fun nonExhaustiveEdgeConditionsMessage(node: String, domainKind: String, missing: List<String>): String {
    val missingText = missing.joinToString(", ")
    return "'$node' routes on $domainKind but no edge handles: $missingText. " +
        "Add an edge for the missing case${if (missing.size > 1) "s" else ""}, " +
        "or an unconditional fallback edge from '$node'."
}
