package org.jetbrains.koog.graph.checker.common

/** Severity of a structural finding, mapped by each layer to its own diagnostic level. */
enum class GraphFindingSeverity { ERROR, WARNING, WEAK_WARNING }

/** Which structural rule produced a finding — lets each layer pick the matching diagnostic factory. */
enum class GraphFindingKind {
    /** An edge whose source is the finish node. */ FINISH_OUTGOING_EDGE,
    /** No path of edges reaches the finish node. */ FINISH_UNREACHABLE,
    /** Two nodes in the same graph share a name. */ DUPLICATE_NODE_NAME,
    /** A declared node that no path from start reaches. */ UNREACHABLE_NODE,
    /** A conditional edge ordered after an unconditional one from the same node. */ SHADOWED_EDGE,
    /** A reachable, non-finish node with no outgoing edge. */ DEAD_END_NODE,
    /** All outgoing edges from a node are conditional with no catch-all fallback. */ ALL_CONDITIONAL_NO_FALLBACK,
    /** An enumerable domain (enum/sealed/boolean) is not fully covered by edge conditions. */ NON_EXHAUSTIVE_EDGE_CONDITIONS,
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
 * Runs all structural checks over a [GraphModel] and returns the findings. This is the single shared
 * implementation: both the FIR checker and the IDE inspection build a model from their own AST and
 * call this.
 *
 * The reachability-based checks (finish-unreachable, unreachable node, dead-end node) only run when
 * every edge resolved to a known source and target — an unresolved edge means the graph shape is
 * uncertain, and silence is preferable to a false alarm. The order-/name-based checks (finish
 * outgoing edge, duplicate name, shadowed edge) do not need connectivity and always run.
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
        findings += exhaustivenessFindings(model, reachable)
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

/** Every edge whose source is the finish node — the finish node cannot have outgoing edges. */
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

/** Declared nodes sharing an effective name; report each declaration after the first. */
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

/** Within one source node's edges, any edge declared after an unconditional one is shadowed. */
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

/** The finish node is not in the reachable set, so the graph can never terminate. */
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

/** Declared nodes that no path from start reaches. */
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

/** Reachable, non-finish nodes with no outgoing edge — execution would stall there. */
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

/**
 * Edge-condition exhaustiveness check.
 *
 * For each reachable node with outgoing edges, checks whether the edge conditions fully cover the
 * node's emitted domain. Implements three tiers:
 *
 * 1. **Enumerable domain, all pure discriminators** → report missing cases (`NON_EXHAUSTIVE_EDGE_CONDITIONS`).
 * 2. **Enumerable domain, some opaque conditions** → suppress (can't prove a gap).
 * 3. **Non-enumerable / unknown domain, no catch-all** → weak `ALL_CONDITIONAL_NO_FALLBACK` nudge.
 *
 * A [EdgeCondition.CatchAll] edge (bare `forwardTo` or `onCondition { true }`) immediately exhausts
 * the domain → no finding. An [EdgeCondition.Opaque] edge forces bail-out for enumerable domains
 * (since it might cover unknown cases), but does NOT suppress the weak nudge for non-enumerable ones.
 */
private fun <A> exhaustivenessFindings(model: GraphModel<A>, reachable: Set<String>): List<GraphFinding<A>> {
    val findings = mutableListOf<GraphFinding<A>>()
    val edgesBySource = model.edges.groupBy { it.sourceName }

    for (node in model.nodes) {
        if (node.kind == NodeKind.FINISH) continue
        if (node.referenceName !in reachable) continue
        val anchor = node.declarationAnchor ?: continue

        val edges = edgesBySource[node.referenceName] ?: continue
        if (edges.isEmpty()) continue

        if (edges.any { it.condition is EdgeCondition.CatchAll }) continue

        val domain = node.emittedDomain
        val hasOpaque = edges.any { it.condition is EdgeCondition.Opaque }

        if (domain == null || domain is NodeDomain.NonEnumerable || hasOpaque) {
            if (domain == null || domain is NodeDomain.NonEnumerable) {
                findings += GraphFinding(
                    GraphFindingKind.ALL_CONDITIONAL_NO_FALLBACK,
                    GraphFindingSeverity.WEAK_WARNING,
                    anchor,
                    allConditionalNoFallbackMessage(node.referenceName),
                )
            }
            continue
        }

        val covered = edges.mapNotNull { edge ->
            when (val cond = edge.condition) {
                is EdgeCondition.ValueMatch -> cond.valueName
                is EdgeCondition.TypeCheck -> cond.typeName
                else -> null
            }
        }.toSet()

        val allCases = when (domain) {
            is NodeDomain.EnumDomain -> domain.entries
            is NodeDomain.SealedDomain -> domain.subtypes
            is NodeDomain.BooleanDomain -> listOf("true", "false")
            is NodeDomain.NonEnumerable -> continue
        }

        val missing = allCases.filter { it !in covered }

        if (missing.isNotEmpty()) {
            val domainKind = when (domain) {
                is NodeDomain.EnumDomain -> "enum ${domain.className}"
                is NodeDomain.SealedDomain -> "sealed type ${domain.className}"
                is NodeDomain.BooleanDomain -> "Boolean"
                is NodeDomain.NonEnumerable -> continue
            }
            findings += GraphFinding(
                GraphFindingKind.NON_EXHAUSTIVE_EDGE_CONDITIONS,
                GraphFindingSeverity.WARNING,
                anchor,
                nonExhaustiveEdgeConditionsMessage(node.referenceName, domainKind, missing),
            )
        }
    }

    return findings
}
