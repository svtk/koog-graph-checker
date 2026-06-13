package org.jetbrains.koog.graph.checker.common

/** Severity of a structural finding, mapped by each layer to its own diagnostic level. */
enum class GraphFindingSeverity { ERROR, WARNING }

/** Which structural rule produced a finding — lets each layer pick the matching diagnostic factory. */
enum class GraphFindingKind {
    /** §2.1 */ FINISH_OUTGOING_EDGE,
    /** §2.2 */ FINISH_UNREACHABLE,
    /** §2.3 */ DUPLICATE_NODE_NAME,
    /** §2.4 */ UNREACHABLE_NODE,
    /** §2.5 */ SHADOWED_EDGE,
    /** §2.6 */ DEAD_END_NODE,
    /** §2.8/§2.9 — an enumerable fan-out (enum/sealed/boolean/null) leaves some case unhandled. */
    MISSING_EDGE_CASES,
    /** §2.7 — a fan-out whose edges are all conditional with no fallback (opt-in; see [analyzeExhaustiveness]). */
    ALL_CONDITIONAL_FANOUT,
}

/** A single structural problem: where to report ([anchor]), how severe, and the shared message text. */
class GraphFinding<A>(
    val kind: GraphFindingKind,
    val severity: GraphFindingSeverity,
    val anchor: A,
    val message: String,
)

private const val START = "nodeStart"
private const val FINISH = "nodeFinish"

/**
 * Runs the structural checks (spec §2.1–§2.6) over a [GraphModel] and returns the findings. This is
 * the single shared implementation: both the FIR checker and the IDE inspection build a model from
 * their own AST and call this.
 *
 * Reachability-based checks (§2.2 finish-unreachable, §2.4 unreachable node, §2.6 dead-end node) only
 * run when every edge resolved to a known source and target — an unresolved edge means the graph
 * shape is uncertain, and silence is preferable to a false alarm. The order-/name-based checks (§2.1,
 * §2.3, §2.5) do not need connectivity and always run.
 */
fun <A> analyzeGraph(model: GraphModel<A>): List<GraphFinding<A>> {
    val findings = mutableListOf<GraphFinding<A>>()

    findings += finishOutgoingEdges(model)
    findings += duplicateNodeNames(model)
    findings += shadowedEdges(model)

    val allEdgesResolved = model.edges.all { it.sourceName != null && it.targetName != null }
    if (allEdgesResolved && model.edges.isNotEmpty()) {
        val reachable = reachableFrom(START, model)
        findings += finishUnreachable(model, reachable)
        findings += unreachableNodes(model, reachable)
        findings += deadEndNodes(model, reachable)
    }

    return findings
}

/** Forward-reachable set from [start], following edge targets in declaration order (mirrors Koog's DFS). */
private fun <A> reachableFrom(start: String, model: GraphModel<A>): Set<String> {
    val outgoing = model.edges.groupBy { it.sourceName }
    val visited = mutableSetOf<String>()
    fun visit(name: String) {
        if (!visited.add(name)) return
        outgoing[name]?.forEach { edge -> edge.targetName?.let(::visit) }
    }
    visit(start)
    return visited
}

/** §2.1 — every edge whose source is the finish node. */
private fun <A> finishOutgoingEdges(model: GraphModel<A>): List<GraphFinding<A>> =
    model.edges
        .filter { it.sourceName == FINISH && it.sourceAnchor != null }
        .map { edge ->
            GraphFinding(
                GraphFindingKind.FINISH_OUTGOING_EDGE,
                GraphFindingSeverity.ERROR,
                edge.sourceAnchor!!,
                finishOutgoingEdgeMessage(FINISH),
            )
        }

/** §2.3 — declared nodes sharing an effective name; report each declaration after the first. */
private fun <A> duplicateNodeNames(model: GraphModel<A>): List<GraphFinding<A>> {
    val findings = mutableListOf<GraphFinding<A>>()
    val firstByName = mutableMapOf<String, GraphNode<A>>()
    for (node in model.nodes) {
        if (node.kind != NodeKind.NODE && node.kind != NodeKind.SUBGRAPH) continue
        val anchor = node.declarationAnchor ?: continue
        val first = firstByName[node.effectiveName]
        if (first == null) {
            firstByName[node.effectiveName] = node
        } else {
            findings += GraphFinding(
                GraphFindingKind.DUPLICATE_NODE_NAME,
                GraphFindingSeverity.ERROR,
                anchor,
                duplicateNodeNameMessage(node.effectiveName, first.referenceName),
            )
        }
    }
    return findings
}

/** §2.5 — within one source node's edges, any edge declared after an unconditional one is shadowed. */
private fun <A> shadowedEdges(model: GraphModel<A>): List<GraphFinding<A>> {
    val findings = mutableListOf<GraphFinding<A>>()
    for ((source, edges) in model.edges.groupBy { it.sourceName }) {
        if (source == null) continue // can't attribute an ordering to an unresolved source
        var seenUnconditional = false
        for (edge in edges) {
            if (seenUnconditional) {
                findings += GraphFinding(
                    GraphFindingKind.SHADOWED_EDGE,
                    GraphFindingSeverity.WARNING,
                    edge.edgeAnchor,
                    shadowedEdgeMessage(source),
                )
            }
            if (!edge.conditional) seenUnconditional = true
        }
    }
    return findings
}

/** §2.2 — the finish node is not in the reachable set. */
private fun <A> finishUnreachable(model: GraphModel<A>, reachable: Set<String>): List<GraphFinding<A>> =
    if (FINISH in reachable) {
        emptyList()
    } else {
        listOf(
            GraphFinding(
                GraphFindingKind.FINISH_UNREACHABLE,
                GraphFindingSeverity.ERROR,
                model.scopeAnchor,
                finishUnreachableMessage(model.scopeName),
            )
        )
    }

/** §2.4 — declared nodes that no path from start reaches. */
private fun <A> unreachableNodes(model: GraphModel<A>, reachable: Set<String>): List<GraphFinding<A>> =
    model.nodes
        .filter { it.kind == NodeKind.NODE || it.kind == NodeKind.SUBGRAPH }
        .filter { it.declarationAnchor != null && it.referenceName !in reachable }
        .map { node ->
            GraphFinding(
                GraphFindingKind.UNREACHABLE_NODE,
                GraphFindingSeverity.WARNING,
                node.declarationAnchor!!,
                unreachableNodeMessage(node.referenceName),
            )
        }

/** §2.6 — reachable, non-finish nodes with no outgoing edge. */
private fun <A> deadEndNodes(model: GraphModel<A>, reachable: Set<String>): List<GraphFinding<A>> {
    val hasOutgoing = model.edges.mapNotNull { it.sourceName }.toSet()
    return model.nodes
        .filter { it.kind == NodeKind.NODE || it.kind == NodeKind.SUBGRAPH }
        .filter { it.declarationAnchor != null }
        .filter { it.referenceName in reachable && it.referenceName !in hasOutgoing }
        .map { node ->
            GraphFinding(
                GraphFindingKind.DEAD_END_NODE,
                GraphFindingSeverity.WARNING,
                node.declarationAnchor!!,
                deadEndNodeMessage(node.referenceName),
            )
        }
}
