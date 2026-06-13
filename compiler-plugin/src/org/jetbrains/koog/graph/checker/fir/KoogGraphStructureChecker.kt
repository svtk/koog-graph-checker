package org.jetbrains.koog.graph.checker.fir

import org.jetbrains.koog.graph.checker.common.GraphEdge
import org.jetbrains.koog.graph.checker.common.GraphFinding
import org.jetbrains.koog.graph.checker.common.GraphFindingKind
import org.jetbrains.koog.graph.checker.common.GraphModel
import org.jetbrains.koog.graph.checker.common.GraphNode
import org.jetbrains.koog.graph.checker.common.NodeKind
import org.jetbrains.koog.graph.checker.common.analyzeGraph
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedDelegateExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.name.Name

/**
 * Reports the structural graph diagnostics for a single Koog `strategy { }` / `subgraph { }` block.
 *
 * Unlike [KoogEdgeTypeMismatchChecker] (which is per-edge and type-driven), these checks need a view
 * of the whole block. The checker fires once on each `strategy`/`subgraph` call, reconstructs the
 * block's graph into the shared layer-agnostic [GraphModel] — implicit `nodeStart`/`nodeFinish`, every
 * `val x by node(...)`/`subgraph(...)` declaration, and every edge built by `edge(...)` or the `then`
 * infix operator — and hands it to the shared [analyzeGraph]. The IDE inspection builds the very same
 * model from PSI and runs the very same analysis, keeping both layers in lockstep.
 *
 * Each block is analyzed on its own: a nested `subgraph { }` appears here only as an opaque
 * [NodeKind.SUBGRAPH] node and is analyzed independently when the checker fires on its own call, so
 * there is no recursion and no double-reporting.
 */
object KoogGraphStructureChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    private val STRATEGY = Name.identifier("strategy")
    private val SUBGRAPH = Name.identifier("subgraph")
    private val EDGE = Name.identifier("edge")
    private val THEN = Name.identifier("then")
    private val FORWARD_TO = Name.identifier("forwardTo")
    private val PROVIDE_DELEGATE = Name.identifier("provideDelegate")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val callee = expression.calleeReference.name
        if (callee != STRATEGY && callee != SUBGRAPH) return

        val model = buildModel(expression) ?: return
        for (finding in analyzeGraph(model)) {
            reporter.reportOn(finding.anchor, finding.kind.factory(), finding.message, context)
        }
    }

    private fun GraphFindingKind.factory(): KtDiagnosticFactory1<String> = when (this) {
        GraphFindingKind.FINISH_OUTGOING_EDGE -> KoogDiagnostics.KOOG_FINISH_OUTGOING_EDGE
        GraphFindingKind.FINISH_UNREACHABLE -> KoogDiagnostics.KOOG_FINISH_UNREACHABLE
        GraphFindingKind.DUPLICATE_NODE_NAME -> KoogDiagnostics.KOOG_DUPLICATE_NODE_NAME
        GraphFindingKind.UNREACHABLE_NODE -> KoogDiagnostics.KOOG_UNREACHABLE_NODE
        GraphFindingKind.SHADOWED_EDGE -> KoogDiagnostics.KOOG_SHADOWED_EDGE
        GraphFindingKind.DEAD_END_NODE -> KoogDiagnostics.KOOG_DEAD_END_NODE
    }

    /** Reconstructs the block's graph from the trailing-lambda body. */
    private fun buildModel(call: FirFunctionCall): GraphModel<KtSourceElement>? {
        val lambda = call.arguments.filterIsInstance<FirAnonymousFunctionExpression>().lastOrNull() ?: return null
        val body = lambda.anonymousFunction.body ?: return null
        val scopeAnchor = call.calleeReference.source ?: call.source ?: return null

        val nodes = mutableListOf(
            GraphNode<KtSourceElement>("nodeStart", "nodeStart", NodeKind.START, null),
            GraphNode<KtSourceElement>("nodeFinish", "nodeFinish", NodeKind.FINISH, null),
        )
        val edges = mutableListOf<GraphEdge<KtSourceElement>>()

        for (statement in body.statements) {
            when {
                statement is FirProperty -> statement.toGraphNode()?.let(nodes::add)
                statement is FirFunctionCall && statement.calleeReference.name == EDGE ->
                    statement.toEdge()?.let(edges::add)
                statement is FirFunctionCall && statement.calleeReference.name == THEN ->
                    statement.toThenEdges(edges)
            }
        }

        return GraphModel(call.firstStringLiteralArg(), scopeAnchor, nodes, edges)
    }

    /** A `val x by node(...)` / `nodeLLMRequest(...)` / `subgraph(...) { }` declaration, or null. */
    private fun FirProperty.toGraphNode(): GraphNode<KtSourceElement>? {
        val delegateCall = delegate?.unwrapDelegateCall() ?: return null
        val delegateName = delegateCall.calleeReference.name.asString()
        val kind = when {
            delegateName == "subgraph" -> NodeKind.SUBGRAPH
            delegateName.startsWith("node") -> NodeKind.NODE
            else -> return null
        }
        val propertyName = name.asString()
        val source = source ?: return null
        return GraphNode(propertyName, delegateCall.firstStringLiteralArg() ?: propertyName, kind, source)
    }

    /** An `edge(source forwardTo target …)` statement. */
    private fun FirFunctionCall.toEdge(): GraphEdge<KtSourceElement>? {
        val builder = arguments.firstOrNull() as? FirFunctionCall ?: return null
        val forwardTo = builder.findForwardToCall()
        val sourceRef = forwardTo?.explicitReceiver
        val anchor = calleeReference.source ?: source ?: return null
        return GraphEdge(
            sourceName = sourceRef?.simpleNameOrNull(),
            targetName = forwardTo?.arguments?.firstOrNull()?.simpleNameOrNull(),
            conditional = builder.chainHasConditionOperator(),
            edgeAnchor = anchor,
            sourceAnchor = sourceRef?.source,
        )
    }

    /** A `source then target` infix call (also handles `a then b then c` chains), appended to [out]. */
    private fun FirFunctionCall.toThenEdges(out: MutableList<GraphEdge<KtSourceElement>>) {
        val target = arguments.firstOrNull() ?: return
        val receiver = explicitReceiver
        val sourceRef = if (receiver is FirFunctionCall && receiver.calleeReference.name == THEN) {
            receiver.arguments.firstOrNull()
        } else {
            receiver
        }
        val anchor = calleeReference.source ?: source
        if (anchor != null) {
            out += GraphEdge(
                sourceName = sourceRef?.simpleNameOrNull(),
                targetName = target.simpleNameOrNull(),
                conditional = false,
                edgeAnchor = anchor,
                sourceAnchor = sourceRef?.source,
            )
        }
        if (receiver is FirFunctionCall && receiver.calleeReference.name == THEN) receiver.toThenEdges(out)
    }

    /** Unwraps the `by` delegate to the underlying `node(...)`/`subgraph(...)` call. */
    private fun FirExpression.unwrapDelegateCall(): FirFunctionCall? {
        val expression = (this as? FirWrappedDelegateExpression)?.expression ?: this
        var call = expression as? FirFunctionCall ?: return null
        while (call.calleeReference.name == PROVIDE_DELEGATE) {
            call = call.explicitReceiver as? FirFunctionCall ?: return call
        }
        return call
    }

    /** Walks the explicit-receiver chain to the innermost `forwardTo` call (`source forwardTo target`). */
    private fun FirFunctionCall.findForwardToCall(): FirFunctionCall? {
        var current: FirExpression? = this
        while (current is FirFunctionCall) {
            if (current.calleeReference.name == FORWARD_TO) return current
            current = current.explicitReceiver
        }
        return null
    }

    /** True if the builder chain applies any condition operator (`onCondition`, `onIsInstance`, …). */
    private fun FirFunctionCall.chainHasConditionOperator(): Boolean {
        var current: FirExpression? = this
        while (current is FirFunctionCall) {
            if (current.calleeReference.name.asString().startsWith("on")) return true
            current = current.explicitReceiver
        }
        return false
    }

    /** The `val`/property name of a bare node reference (e.g. `source`, `nodeStart`), or null. */
    private fun FirExpression.simpleNameOrNull(): String? {
        val access = this as? FirPropertyAccessExpression ?: return null
        if (access.explicitReceiver != null) return null
        return access.calleeReference.name.asString()
    }

    private fun FirFunctionCall.firstStringLiteralArg(): String? =
        arguments.firstNotNullOfOrNull { (it as? FirLiteralExpression)?.value as? String }
}
