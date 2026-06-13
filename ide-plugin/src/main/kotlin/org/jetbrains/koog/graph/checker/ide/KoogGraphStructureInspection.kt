@file:OptIn(KaExperimentalApi::class)

package org.jetbrains.koog.graph.checker.ide

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.koog.graph.checker.common.DomainCase
import org.jetbrains.koog.graph.checker.common.EdgeCoverage
import org.jetbrains.koog.graph.checker.common.EdgeDomain
import org.jetbrains.koog.graph.checker.common.GraphEdge
import org.jetbrains.koog.graph.checker.common.GraphFindingSeverity
import org.jetbrains.koog.graph.checker.common.GraphModel
import org.jetbrains.koog.graph.checker.common.GraphNode
import org.jetbrains.koog.graph.checker.common.NodeFanOut
import org.jetbrains.koog.graph.checker.common.NodeKind
import org.jetbrains.koog.graph.checker.common.analyzeExhaustiveness
import org.jetbrains.koog.graph.checker.common.analyzeGraph
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeArgumentWithVariance
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.lexer.KtTokens
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
 * Reports the structural graph diagnostics (spec §2.1–§2.6) and the edge-condition exhaustiveness
 * diagnostics (spec §2.7–§2.9) for a Koog `strategy { }` / `subgraph { }` block, the IDE counterpart
 * of the compiler's [org.jetbrains.koog.graph.checker.fir.KoogGraphStructureChecker].
 *
 * It builds the shared, layer-agnostic [GraphModel] from PSI — implicit `nodeStart`/`nodeFinish`,
 * every `val x by node(...)`/`subgraph(...)` declaration, and every edge built by `edge(...)` or the
 * `then` infix operator — and runs the exact same [analyzeGraph] the compiler uses, so the two layers
 * stay in lockstep and the diagnostic text is byte-identical. For exhaustiveness it additionally
 * groups edges by source node into the shared [NodeFanOut] model (classifying each source's emitted
 * type and reading each edge's condition into an [EdgeCoverage] via the Analysis API) and runs the
 * same [analyzeExhaustiveness]. Each block is analyzed on its own; a nested `subgraph { }` is an
 * opaque [NodeKind.SUBGRAPH] node here and is analyzed independently when the visitor reaches its own
 * call.
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
                analyze(expression) {
                    for (finding in analyzeExhaustiveness(buildFanOuts(expression))) {
                        holder.registerProblem(finding.anchor, finding.message, finding.severity.highlightType())
                    }
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

    // ---- Edge-condition exhaustiveness (spec §2.7–§2.9) -------------------------------------------

    /**
     * Groups the block's outgoing edges by source node into the shared [NodeFanOut] model: the
     * source's classified domain plus each edge's [EdgeCoverage], in declaration order — the IDE twin
     * of the compiler checker's `buildFanOuts`. `then` edges are unconditional, so they contribute a
     * [EdgeCoverage.CatchAll]; edges out of `nodeFinish` are skipped (that is §2.1).
     */
    private fun KaSession.buildFanOuts(call: KtCallExpression): List<NodeFanOut<PsiElement>> {
        val body = call.lambdaArguments.lastOrNull()?.getLambdaExpression()?.bodyExpression ?: return emptyList()
        val scopeAnchor = call.calleeExpression ?: return emptyList()

        val coveragesBySource = LinkedHashMap<String, MutableList<EdgeCoverage>>()
        val anchorBySource = HashMap<String, PsiElement>()
        val subjectBySource = HashMap<String, KaType>()

        fun record(source: String?, anchor: PsiElement?, coverage: EdgeCoverage, subject: KaType?) {
            if (source == null || source == "nodeFinish") return
            coveragesBySource.getOrPut(source) { mutableListOf() }.add(coverage)
            if (anchor != null) anchorBySource.putIfAbsent(source, anchor)
            if (subject != null) subjectBySource.putIfAbsent(source, subject)
        }

        for (statement in body.statements) {
            when (statement) {
                is KtCallExpression ->
                    if (statement.calleeExpression?.text == "edge") {
                        val builder = statement.valueArguments.firstOrNull()?.getArgumentExpression()
                        val sourceRef = builder?.findForwardToExpression()?.left as? KtNameReferenceExpression
                        if (builder != null) {
                            record(sourceRef?.text, sourceRef, extractCoverage(builder), subjectType(builder))
                        }
                    }
                is KtBinaryExpression ->
                    if (statement.operationReference.text == "then") {
                        statement.recordThenSources { source, anchor -> record(source, anchor, EdgeCoverage.CatchAll, null) }
                    }
                else -> {}
            }
        }

        return coveragesBySource.mapNotNull { (source, coverages) ->
            val anchor = anchorBySource[source] ?: scopeAnchor
            val domain = subjectBySource[source]?.let { classifyDomain(it) } ?: EdgeDomain.NonEnumerable
            NodeFanOut(source, anchor, domain, coverages)
        }
    }

    /** Emits the source node (and its anchor) of each edge in a `a then b then c` chain. */
    private fun KtBinaryExpression.recordThenSources(emit: (String?, PsiElement?) -> Unit) {
        val left = left
        val sourceRef = if (left is KtBinaryExpression && left.operationReference.text == "then") {
            left.right as? KtNameReferenceExpression
        } else {
            left as? KtNameReferenceExpression
        }
        emit(sourceRef?.text, sourceRef)
        if (left is KtBinaryExpression && left.operationReference.text == "then") left.recordThenSources(emit)
    }

    /** The value the edge routes on: `IncomingOutput` = builder type argument 0 (the source's output). */
    private fun KaSession.subjectType(expression: KtExpression): KaType? {
        val builderType = expression.expressionType as? KaClassType ?: return null
        if (builderType.classId != EDGE_BUILDER_CLASS_ID) return null
        return (builderType.typeArguments.firstOrNull() as? KaTypeArgumentWithVariance)?.type
    }

    /** Classifies a fan-out subject type into a [EdgeDomain] (the §2.9 domain classifier). */
    private fun KaSession.classifyDomain(subject: KaType): EdgeDomain {
        val nullable = subject.nullability.isNullable
        val classType = subject as? KaClassType ?: return EdgeDomain.NonEnumerable
        if (classType.classId == ClassId(FqName("kotlin"), Name.identifier("Boolean"))) {
            return EdgeDomain.Enumerable(booleanCases(nullable), "Boolean")
        }
        val symbol = classType.symbol as? KaNamedClassSymbol ?: return EdgeDomain.NonEnumerable
        if (symbol.classKind == KaClassKind.ENUM_CLASS) {
            val entries = symbol.staticDeclaredMemberScope.callables
                .filterIsInstance<KaEnumEntrySymbol>()
                .map { it.name.asString() }
                .toList()
            if (entries.isEmpty()) return EdgeDomain.NonEnumerable
            val cases = entries.map { DomainCase(it, it) } + nullCase(nullable)
            return EdgeDomain.Enumerable(cases, "enum ${symbol.name.asString()}")
        }
        val inheritors = symbol.sealedClassInheritors
        if (inheritors.isEmpty()) return EdgeDomain.NonEnumerable
        val cases = inheritors.mapNotNull { it.classId }
            .map { DomainCase(it.asFqNameString(), "is ${it.shortClassName.asString()}") } + nullCase(nullable)
        if (cases.isEmpty()) return EdgeDomain.NonEnumerable
        return EdgeDomain.Enumerable(cases, symbol.name.asString())
    }

    private fun booleanCases(nullable: Boolean): List<DomainCase> =
        listOf(DomainCase("true", "true"), DomainCase("false", "false")) + nullCase(nullable)

    private fun nullCase(nullable: Boolean): List<DomainCase> =
        if (nullable) listOf(DomainCase("null", "null")) else emptyList()

    /**
     * Reads one edge's builder chain into an [EdgeCoverage] (the §2.9 pure-discriminator extractor):
     * a bare `forwardTo` (optionally `transformed`) is a catch-all; a single recognized discriminator
     * (`onCondition { it == … }` / `onIsInstance(T::class)`) covers its case; anything else is opaque.
     */
    private fun KaSession.extractCoverage(builder: KtExpression): EdgeCoverage {
        val steps = builder.operatorChain() ?: return EdgeCoverage.Opaque
        val conditionSteps = steps.filter { it.name.startsWith("on") }
        if (conditionSteps.isEmpty()) return EdgeCoverage.CatchAll // bare forwardTo, possibly with transformed
        if (steps.size > 1) return EdgeCoverage.Opaque // extra operators ⇒ not a clean discriminator
        val step = conditionSteps.single()
        return when (step.name) {
            "onCondition" -> coverageFromOnCondition(step.argument)
            "onIsInstance" -> coverageFromOnIsInstance(step)
            else -> EdgeCoverage.Opaque
        }
    }

    /** One operator applied to the edge builder, e.g. `onCondition { … }`, with its argument. */
    private class OperatorStep(val name: String, val argument: KtExpression?)

    /** Walks the builder chain outermost-to-`forwardTo`, listing the operators; null if `forwardTo` is absent. */
    private fun KtExpression.operatorChain(): List<OperatorStep>? {
        val steps = mutableListOf<OperatorStep>()
        var current: KtExpression? = this
        while (current != null) {
            when (current) {
                is KtBinaryExpression -> {
                    val name = current.operationReference.text
                    if (name == "forwardTo") return steps
                    steps.add(OperatorStep(name, current.right))
                    current = current.left
                }
                is KtDotQualifiedExpression -> {
                    val selector = current.selectorExpression as? KtCallExpression ?: return null
                    val name = selector.calleeExpression?.text ?: return null
                    if (name == "forwardTo") return steps
                    val arg = selector.lambdaArguments.firstOrNull()?.getLambdaExpression()
                        ?: selector.valueArguments.firstOrNull()?.getArgumentExpression()
                    steps.add(OperatorStep(name, arg))
                    current = current.receiverExpression
                }
                else -> return null
            }
        }
        return null
    }

    private fun KaSession.coverageFromOnCondition(argument: KtExpression?): EdgeCoverage {
        val lambda = argument as? KtLambdaExpression ?: return EdgeCoverage.Opaque
        val expr = lambda.bodyExpression?.statements?.singleOrNull() ?: return EdgeCoverage.Opaque
        if (expr is KtConstantExpression && expr.text == "true") return EdgeCoverage.CatchAll
        val binary = expr as? KtBinaryExpression ?: return EdgeCoverage.Opaque
        if (binary.operationToken != KtTokens.EQEQ) return EdgeCoverage.Opaque
        return listOfNotNull(binary.left, binary.right)
            .firstNotNullOfOrNull { constantOperandCoverage(it) }
            ?: EdgeCoverage.Opaque
    }

    /** Coverage from a `== <constant>` operand: a boolean/`null` literal or an enum entry; else null. */
    private fun KaSession.constantOperandCoverage(operand: KtExpression): EdgeCoverage? = when {
        operand is KtConstantExpression && operand.text == "true" -> EdgeCoverage.Covers(listOf("true"))
        operand is KtConstantExpression && operand.text == "false" -> EdgeCoverage.Covers(listOf("false"))
        operand is KtConstantExpression && operand.text == "null" -> EdgeCoverage.Covers(listOf("null"))
        else -> resolveEnumEntryName(operand)?.let { EdgeCoverage.Covers(listOf(it)) }
    }

    /** The enum-entry name `operand` refers to (e.g. `Route.SEARCH` → "SEARCH"), or null if it is not one. */
    private fun KaSession.resolveEnumEntryName(operand: KtExpression): String? {
        val reference = when (operand) {
            is KtDotQualifiedExpression -> (operand.selectorExpression as? KtNameReferenceExpression)?.mainReference
            is KtNameReferenceExpression -> operand.mainReference
            else -> null
        } ?: return null
        return (resolveToSymbol(reference) as? KaEnumEntrySymbol)?.name?.asString()
    }

    private fun KaSession.coverageFromOnIsInstance(step: OperatorStep): EdgeCoverage {
        val classLiteral = step.argument as? KtClassLiteralExpression ?: return EdgeCoverage.Opaque
        val kClassType = classLiteral.expressionType as? KaClassType ?: return EdgeCoverage.Opaque
        val target = (kClassType.typeArguments.firstOrNull() as? KaTypeArgumentWithVariance)?.type as? KaClassType
        val classId = target?.classId ?: return EdgeCoverage.Opaque
        return EdgeCoverage.Covers(listOf(classId.asFqNameString()))
    }
}

private val EDGE_BUILDER_CLASS_ID = ClassId(
    FqName("ai.koog.agents.core.dsl.builder"),
    Name.identifier("AIAgentEdgeBuilderIntermediate"),
)
