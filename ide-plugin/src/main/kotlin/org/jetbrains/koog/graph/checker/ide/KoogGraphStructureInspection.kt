package org.jetbrains.koog.graph.checker.ide

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.koog.graph.checker.common.GraphEdge
import org.jetbrains.koog.graph.checker.common.GraphFindingSeverity
import org.jetbrains.koog.graph.checker.common.GraphModel
import org.jetbrains.koog.graph.checker.common.GraphNode
import org.jetbrains.koog.graph.checker.common.NodeKind
import org.jetbrains.koog.graph.checker.common.analyzeGraph
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Reports the structural graph diagnostics (spec §2.1–§2.6) for a Koog `strategy { }` / `subgraph { }`
 * block, the IDE counterpart of the compiler's [org.jetbrains.koog.graph.checker.fir.KoogGraphStructureChecker].
 *
 * It builds the shared, layer-agnostic [GraphModel] from PSI — implicit `nodeStart`/`nodeFinish`,
 * every `val x by node(...)`/`subgraph(...)` declaration, and every edge built by `edge(...)` or the
 * `then` infix operator — and runs the exact same [analyzeGraph] the compiler uses, so the two layers
 * stay in lockstep and the diagnostic text is byte-identical. Each block is analyzed on its own; a
 * nested `subgraph { }` is an opaque [NodeKind.SUBGRAPH] node here and is analyzed independently when
 * the visitor reaches its own call.
 */
class KoogGraphStructureInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : KtVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression?.text
                if (callee != "strategy" && callee != "subgraph") return

                val model = buildModel(expression) ?: return
                for (finding in analyzeGraph(model)) {
                    holder.registerProblem(finding.anchor, finding.message, finding.severity.highlightType())
                }
            }
        }

    private fun GraphFindingSeverity.highlightType(): ProblemHighlightType = when (this) {
        GraphFindingSeverity.ERROR -> ProblemHighlightType.GENERIC_ERROR
        GraphFindingSeverity.WARNING -> ProblemHighlightType.WARNING
    }

    /** Reconstructs the block's graph from the trailing-lambda body. */
    private fun buildModel(call: KtCallExpression): GraphModel<PsiElement>? {
        val body = call.lambdaArguments.lastOrNull()?.getLambdaExpression()?.bodyExpression ?: return null
        val scopeAnchor = call.calleeExpression ?: return null

        val nodes = mutableListOf(
            GraphNode<PsiElement>("nodeStart", "nodeStart", NodeKind.START, null),
            GraphNode<PsiElement>("nodeFinish", "nodeFinish", NodeKind.FINISH, null),
        )
        val edges = mutableListOf<GraphEdge<PsiElement>>()

        for (statement in body.statements) {
            when (statement) {
                is KtProperty -> statement.toGraphNode()?.let(nodes::add)
                is KtCallExpression ->
                    if (statement.calleeExpression?.text == "edge") statement.toEdge()?.let(edges::add)
                is KtBinaryExpression ->
                    if (statement.operationReference.text == "then") statement.toThenEdges(edges)
                else -> {}
            }
        }

        return GraphModel(call.firstStringLiteralArg(), scopeAnchor, nodes, edges)
    }

    /** A `val x by node(...)` / `nodeLLMRequest(...)` / `subgraph(...) { }` declaration, or null. */
    private fun KtProperty.toGraphNode(): GraphNode<PsiElement>? {
        val delegateCall = delegateExpression?.unwrapDelegateCall() ?: return null
        val delegateName = delegateCall.calleeExpression?.text ?: return null
        val kind = when {
            delegateName == "subgraph" -> NodeKind.SUBGRAPH
            delegateName.startsWith("node") -> NodeKind.NODE
            else -> return null
        }
        val propertyName = name ?: return null
        val anchor = nameIdentifier ?: this
        return GraphNode(propertyName, delegateCall.firstStringLiteralArg() ?: propertyName, kind, anchor)
    }

    /** An `edge(source forwardTo target …)` statement. */
    private fun KtCallExpression.toEdge(): GraphEdge<PsiElement>? {
        val builder = valueArguments.firstOrNull()?.getArgumentExpression() ?: return null
        val forwardTo = builder.findForwardToExpression()
        val sourceRef = forwardTo?.left as? KtNameReferenceExpression
        return GraphEdge(
            sourceName = sourceRef?.text,
            targetName = (forwardTo?.right as? KtNameReferenceExpression)?.text,
            conditional = builder.chainHasConditionOperator(),
            edgeAnchor = this,
            sourceAnchor = sourceRef,
        )
    }

    /** A `source then target` infix statement (also handles `a then b then c` chains), appended to [out]. */
    private fun KtBinaryExpression.toThenEdges(out: MutableList<GraphEdge<PsiElement>>) {
        val target = right as? KtNameReferenceExpression
        val left = left
        val sourceRef = if (left is KtBinaryExpression && left.operationReference.text == "then") {
            left.right as? KtNameReferenceExpression
        } else {
            left as? KtNameReferenceExpression
        }
        out += GraphEdge(
            sourceName = sourceRef?.text,
            targetName = target?.text,
            conditional = false,
            edgeAnchor = this,
            sourceAnchor = sourceRef,
        )
        if (left is KtBinaryExpression && left.operationReference.text == "then") left.toThenEdges(out)
    }

    /** Unwraps the `by` delegate to the underlying `node(...)`/`subgraph(...)` call. */
    private fun KtExpression.unwrapDelegateCall(): KtCallExpression? = when (this) {
        is KtCallExpression -> this
        is KtDotQualifiedExpression -> (selectorExpression as? KtCallExpression)
            ?.takeIf { it.calleeExpression?.text == "provideDelegate" }
            ?.let { receiverExpression.unwrapDelegateCall() }
            ?: (receiverExpression as? KtCallExpression)
        else -> null
    }

    /** Descends the left-operand chain to the innermost `forwardTo` infix call (`source forwardTo target`). */
    private fun KtExpression.findForwardToExpression(): KtBinaryExpression? {
        var current: KtExpression? = this
        while (current is KtBinaryExpression) {
            if (current.operationReference.text == "forwardTo") return current
            current = current.left
        }
        return null
    }

    /** True if the builder chain applies any condition operator (`onCondition`, `onIsInstance`, …). */
    private fun KtExpression.chainHasConditionOperator(): Boolean {
        var current: KtExpression? = this
        while (current != null) {
            when (current) {
                is KtBinaryExpression -> {
                    if (current.operationReference.text.startsWith("on")) return true
                    current = current.left
                }
                is KtDotQualifiedExpression -> {
                    val selector = (current.selectorExpression as? KtCallExpression)?.calleeExpression?.text
                    if (selector != null && selector.startsWith("on")) return true
                    current = current.receiverExpression
                }
                else -> return false
            }
        }
        return false
    }

    private fun KtCallExpression.firstStringLiteralArg(): String? =
        valueArguments.asSequence()
            .mapNotNull { it.getArgumentExpression() as? KtStringTemplateExpression }
            .firstOrNull { template -> template.entries.all { it is KtLiteralStringTemplateEntry } }
            ?.entries?.joinToString("") { it.text }
}
