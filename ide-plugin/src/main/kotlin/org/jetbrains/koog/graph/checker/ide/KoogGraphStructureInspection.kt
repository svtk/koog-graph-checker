@file:OptIn(KaExperimentalApi::class)

package org.jetbrains.koog.graph.checker.ide

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.koog.graph.checker.common.EdgeCondition
import org.jetbrains.koog.graph.checker.common.GraphEdge
import org.jetbrains.koog.graph.checker.common.GraphFindingSeverity
import org.jetbrains.koog.graph.checker.common.GraphModel
import org.jetbrains.koog.graph.checker.common.GraphNode
import org.jetbrains.koog.graph.checker.common.NodeDomain
import org.jetbrains.koog.graph.checker.common.NodeKind
import org.jetbrains.koog.graph.checker.common.analyzeGraph
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeArgumentWithVariance
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Reports the structural graph diagnostics for a Koog `strategy { }` / `subgraph { }` block, the IDE
 * counterpart of the compiler's [org.jetbrains.koog.graph.checker.fir.KoogGraphStructureChecker].
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

                val model = analyze(expression) { buildModel(expression) } ?: return
                for (finding in analyzeGraph(model)) {
                    holder.registerProblem(finding.anchor, finding.message, finding.severity.highlightType())
                }
            }
        }

    private fun GraphFindingSeverity.highlightType(): ProblemHighlightType = when (this) {
        GraphFindingSeverity.ERROR -> ProblemHighlightType.GENERIC_ERROR
        GraphFindingSeverity.WARNING -> ProblemHighlightType.WARNING
        GraphFindingSeverity.WEAK_WARNING -> ProblemHighlightType.WEAK_WARNING
    }

    /** Reconstructs the block's graph from the trailing-lambda body. */
    private fun KaSession.buildModel(call: KtCallExpression): GraphModel<PsiElement>? {
        val body = call.lambdaArguments.lastOrNull()?.getLambdaExpression()?.bodyExpression ?: return null
        val scopeAnchor = call.calleeExpression ?: return null

        val startDomain = startNodeDomain(call)
        val nodes = mutableListOf(
            GraphNode<PsiElement>("nodeStart", "nodeStart", NodeKind.START, startDomain, null),
            GraphNode<PsiElement>("nodeFinish", "nodeFinish", NodeKind.FINISH, null, null),
        )
        val edges = mutableListOf<GraphEdge<PsiElement>>()

        for (statement in body.statements) {
            when (statement) {
                is KtProperty -> toGraphNode(statement)?.let(nodes::add)
                is KtCallExpression ->
                    if (statement.calleeExpression?.text == "edge") toEdge(statement)?.let(edges::add)
                is KtBinaryExpression ->
                    if (statement.operationReference.text == "then") statement.toThenEdges(edges)
                else -> {}
            }
        }

        return GraphModel(call.firstStringLiteralArg(), scopeAnchor, nodes, edges)
    }

    /** A `val x by node(...)` / `nodeLLMRequest(...)` / `subgraph(...) { }` declaration, or null. */
    private fun KaSession.toGraphNode(property: KtProperty): GraphNode<PsiElement>? {
        val delegateCall = property.delegateExpression?.unwrapDelegateCall() ?: return null
        val delegateName = delegateCall.calleeExpression?.text ?: return null
        val kind = when {
            delegateName == "subgraph" -> NodeKind.SUBGRAPH
            delegateName.startsWith("node") -> NodeKind.NODE
            else -> return null
        }
        val propertyName = property.name ?: return null
        val anchor = property.nameIdentifier ?: property
        val domain = outputTypeDomain(property)
        return GraphNode(propertyName, delegateCall.firstStringLiteralArg() ?: propertyName, kind, domain, anchor)
    }

    /** An `edge(source forwardTo target …)` statement. */
    private fun KaSession.toEdge(call: KtCallExpression): GraphEdge<PsiElement>? {
        val builder = call.valueArguments.firstOrNull()?.getArgumentExpression() ?: return null
        val forwardTo = builder.findForwardToExpression()
        val sourceRef = forwardTo?.left as? KtNameReferenceExpression
        return GraphEdge(
            sourceName = sourceRef?.text,
            targetName = (forwardTo?.right as? KtNameReferenceExpression)?.text,
            conditional = builder.chainHasConditionOperator(),
            condition = extractEdgeCondition(builder),
            edgeAnchor = call.calleeExpression ?: call,
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
            condition = EdgeCondition.CatchAll,
            edgeAnchor = operationReference,
            sourceAnchor = sourceRef,
        )
        if (left is KtBinaryExpression && left.operationReference.text == "then") left.toThenEdges(out)
    }

    // ── Edge condition extraction ──────────────────────────────────────────────

    private fun KaSession.extractEdgeCondition(expr: KtExpression): EdgeCondition {
        var conditionExpr: Any? = null
        var conditionName: String? = null
        var current: KtExpression? = expr

        while (current != null) {
            when (current) {
                is KtBinaryExpression -> {
                    val opText = current.operationReference.text
                    if (opText == "forwardTo") break
                    if (opText.startsWith("on")) {
                        if (conditionExpr != null) return EdgeCondition.Opaque
                        conditionExpr = current
                        conditionName = opText
                    }
                    current = current.left
                }
                is KtDotQualifiedExpression -> {
                    val selector = current.selectorExpression as? KtCallExpression
                    val selectorName = selector?.calleeExpression?.text
                    if (selectorName != null && selectorName.startsWith("on")) {
                        if (conditionExpr != null) return EdgeCondition.Opaque
                        conditionExpr = selector
                        conditionName = selectorName
                    }
                    current = current.receiverExpression
                }
                else -> break
            }
        }

        if (conditionExpr == null) return EdgeCondition.CatchAll

        return when (conditionName) {
            "onCondition" -> analyzeOnConditionLambda(conditionExpr)
            "onIsInstance" -> analyzeOnIsInstance(conditionExpr)
            else -> EdgeCondition.Opaque
        }
    }

    private fun KaSession.analyzeOnConditionLambda(condExpr: Any): EdgeCondition {
        val lambda = when (condExpr) {
            is KtBinaryExpression -> condExpr.right as? KtLambdaExpression
            is KtCallExpression -> condExpr.valueArguments.firstOrNull()
                ?.getArgumentExpression() as? KtLambdaExpression
            else -> null
        } ?: return EdgeCondition.Opaque

        val body = lambda.bodyExpression ?: return EdgeCondition.Opaque
        val statement = body.statements.singleOrNull() ?: return EdgeCondition.Opaque

        if (statement is KtConstantExpression && statement.text == "true") return EdgeCondition.CatchAll
        if (statement is KtNameReferenceExpression && statement.text == "true") return EdgeCondition.CatchAll

        if (statement is KtBinaryExpression && statement.operationReference.text == "==") {
            return asDiscriminatorValue(statement.left)
                ?: asDiscriminatorValue(statement.right)
                ?: EdgeCondition.Opaque
        }

        return EdgeCondition.Opaque
    }

    private fun KaSession.asDiscriminatorValue(expr: KtExpression?): EdgeCondition? {
        if (expr == null) return null

        if (expr is KtConstantExpression || expr is KtNameReferenceExpression) {
            if (expr.text == "true") return EdgeCondition.ValueMatch("true")
            if (expr.text == "false") return EdgeCondition.ValueMatch("false")
        }

        val symbol = when (expr) {
            is KtDotQualifiedExpression -> {
                val selector = expr.selectorExpression as? KtNameReferenceExpression ?: return null
                selector.mainReference.resolveToSymbol()
            }
            is KtNameReferenceExpression -> expr.mainReference.resolveToSymbol()
            else -> null
        }

        if (symbol is KaEnumEntrySymbol) return EdgeCondition.ValueMatch(symbol.name.asString())

        return null
    }

    private fun analyzeOnIsInstance(condExpr: Any): EdgeCondition {
        val classLiteral = when (condExpr) {
            is KtBinaryExpression -> condExpr.right as? KtClassLiteralExpression
            is KtCallExpression -> condExpr.valueArguments.firstOrNull()
                ?.getArgumentExpression() as? KtClassLiteralExpression
            else -> null
        } ?: return EdgeCondition.Opaque

        val typeName = (classLiteral.receiverExpression as? KtNameReferenceExpression)?.text
            ?: return EdgeCondition.Opaque
        return EdgeCondition.TypeCheck(typeName)
    }

    // ── Node domain classification ─────────────────────────────────────────────

    private fun KaSession.outputTypeDomain(property: KtProperty): NodeDomain? {
        val propertyType = property.symbol.returnType as? KaClassType ?: return null
        val outputType = (propertyType.typeArguments.getOrNull(1) as? KaTypeArgumentWithVariance)?.type
            ?: return null
        return classifyDomain(outputType)
    }

    private fun KaSession.startNodeDomain(call: KtCallExpression): NodeDomain? {
        val callType = call.expressionType as? KaClassType ?: return null
        val inputType = (callType.typeArguments.getOrNull(0) as? KaTypeArgumentWithVariance)?.type
            ?: return null
        return classifyDomain(inputType)
    }

    private fun KaSession.classifyDomain(type: KaType): NodeDomain {
        val classType = type as? KaClassType ?: return NodeDomain.NonEnumerable

        if (classType.classId == StandardClassIds.Boolean) return NodeDomain.BooleanDomain

        val classSymbol = classType.expandedSymbol ?: return NodeDomain.NonEnumerable

        if (classSymbol is KaNamedClassSymbol) {
            if (classSymbol.classKind == KaClassKind.ENUM_CLASS) {
                val entries = classSymbol.staticDeclaredMemberScope.declarations
                    .filterIsInstance<KaEnumEntrySymbol>()
                    .map { it.name.asString() }
                    .toList()
                return NodeDomain.EnumDomain(classSymbol.name.asString(), entries)
            }
            if (classSymbol.modality == KaSymbolModality.SEALED) {
                val subtypes = classSymbol.sealedClassInheritors.map { it.name.asString() }
                if (subtypes.isNotEmpty()) return NodeDomain.SealedDomain(classSymbol.name.asString(), subtypes)
            }
        }

        return NodeDomain.NonEnumerable
    }

    // ── Shared helpers ─────────────────────────────────────────────────────────

    private fun KtExpression.unwrapDelegateCall(): KtCallExpression? = when (this) {
        is KtCallExpression -> this
        is KtDotQualifiedExpression -> (selectorExpression as? KtCallExpression)
            ?.takeIf { it.calleeExpression?.text == "provideDelegate" }
            ?.let { receiverExpression.unwrapDelegateCall() }
            ?: (receiverExpression as? KtCallExpression)
        else -> null
    }

    private fun KtExpression.findForwardToExpression(): KtBinaryExpression? {
        var current: KtExpression? = this
        while (current is KtBinaryExpression) {
            if (current.operationReference.text == "forwardTo") return current
            current = current.left
        }
        return null
    }

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
