package org.jetbrains.koog.graph.checker.fir

import org.jetbrains.koog.graph.checker.common.DomainCase
import org.jetbrains.koog.graph.checker.common.EdgeCoverage
import org.jetbrains.koog.graph.checker.common.EdgeDomain
import org.jetbrains.koog.graph.checker.common.GraphEdge
import org.jetbrains.koog.graph.checker.common.GraphFinding
import org.jetbrains.koog.graph.checker.common.GraphFindingKind
import org.jetbrains.koog.graph.checker.common.GraphModel
import org.jetbrains.koog.graph.checker.common.GraphNode
import org.jetbrains.koog.graph.checker.common.NodeFanOut
import org.jetbrains.koog.graph.checker.common.NodeKind
import org.jetbrains.koog.graph.checker.common.analyzeExhaustiveness
import org.jetbrains.koog.graph.checker.common.analyzeGraph
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.getSealedClassInheritors
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedDelegateExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedEnumEntrySymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * Reports the structural graph diagnostics (spec §2.1–§2.6) and the edge-condition exhaustiveness
 * diagnostics (spec §2.7–§2.9) for a single Koog `strategy { }` / `subgraph { }` block.
 *
 * Unlike [KoogEdgeTypeMismatchChecker] (which is per-edge and type-driven), these checks need a view
 * of the whole block. The checker fires once on each `strategy`/`subgraph` call, reconstructs the
 * block's graph into the shared layer-agnostic [GraphModel] — implicit `nodeStart`/`nodeFinish`, every
 * `val x by node(...)`/`subgraph(...)` declaration, and every edge built by `edge(...)` or the `then`
 * infix operator — and hands it to the shared [analyzeGraph]. The IDE inspection builds the very same
 * model from PSI and runs the very same analysis, keeping both layers in lockstep.
 *
 * For exhaustiveness (§2.7–§2.9) it additionally groups the block's edges by source node into the
 * shared [NodeFanOut] model — classifying each source's emitted type (enum / sealed / boolean /
 * nullable / non-enumerable) and reading each edge's condition into an [EdgeCoverage] (catch-all /
 * pure discriminator / opaque) — and hands that to the shared [analyzeExhaustiveness]. This is the
 * only part that needs type resolution, so it lives here (and mirrored in the IDE); the coverage
 * arithmetic is shared.
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
        for (finding in analyzeExhaustiveness(buildFanOuts(expression, context.session))) {
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
        GraphFindingKind.MISSING_EDGE_CASES -> KoogDiagnostics.KOOG_MISSING_EDGE_CASES
        GraphFindingKind.ALL_CONDITIONAL_FANOUT -> KoogDiagnostics.KOOG_ALL_CONDITIONAL_FANOUT
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
        val anchor = source ?: calleeReference.source ?: return null
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
        val anchor = source ?: calleeReference.source
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

    // ---- Edge-condition exhaustiveness (spec §2.7–§2.9) -------------------------------------------

    /**
     * Groups the block's outgoing edges by source node into the shared [NodeFanOut] model: the
     * source's classified domain plus each edge's [EdgeCoverage], in declaration order. `then` edges
     * are unconditional, so they contribute a [EdgeCoverage.CatchAll] (a real fallback that must
     * suppress any "missing case" report). Edges out of `nodeFinish` are skipped (that is §2.1).
     */
    private fun buildFanOuts(call: FirFunctionCall, session: FirSession): List<NodeFanOut<KtSourceElement>> {
        val lambda = call.arguments.filterIsInstance<FirAnonymousFunctionExpression>().lastOrNull() ?: return emptyList()
        val body = lambda.anonymousFunction.body ?: return emptyList()
        val scopeAnchor = call.calleeReference.source ?: call.source

        val coveragesBySource = LinkedHashMap<String, MutableList<EdgeCoverage>>()
        val anchorBySource = HashMap<String, KtSourceElement>()
        val subjectBySource = HashMap<String, ConeKotlinType>()

        fun record(source: String?, anchor: KtSourceElement?, coverage: EdgeCoverage, subject: ConeKotlinType?) {
            if (source == null || source == "nodeFinish") return
            coveragesBySource.getOrPut(source) { mutableListOf() }.add(coverage)
            if (anchor != null) anchorBySource.putIfAbsent(source, anchor)
            if (subject != null) subjectBySource.putIfAbsent(source, subject)
        }

        for (statement in body.statements) {
            when {
                statement is FirFunctionCall && statement.calleeReference.name == EDGE ->
                    (statement.arguments.firstOrNull() as? FirFunctionCall)?.let { builder ->
                        val sourceRef = builder.findForwardToCall()?.explicitReceiver
                        record(sourceRef?.simpleNameOrNull(), sourceRef?.source, extractCoverage(builder), builder.subjectType())
                    }
                statement is FirFunctionCall && statement.calleeReference.name == THEN ->
                    statement.recordThenSources { source, anchor -> record(source, anchor, EdgeCoverage.CatchAll, null) }
            }
        }

        return coveragesBySource.mapNotNull { (source, coverages) ->
            val anchor = anchorBySource[source] ?: scopeAnchor ?: return@mapNotNull null
            val domain = subjectBySource[source]?.let { classifyDomain(it, session) } ?: EdgeDomain.NonEnumerable
            NodeFanOut(source, anchor, domain, coverages)
        }
    }

    /** Emits the source node (and its anchor) of each edge in a `a then b then c` chain. */
    private fun FirFunctionCall.recordThenSources(emit: (String?, KtSourceElement?) -> Unit) {
        val receiver = explicitReceiver
        val sourceRef = if (receiver is FirFunctionCall && receiver.calleeReference.name == THEN) {
            receiver.arguments.firstOrNull()
        } else {
            receiver
        }
        emit(sourceRef?.simpleNameOrNull(), sourceRef?.source)
        if (receiver is FirFunctionCall && receiver.calleeReference.name == THEN) receiver.recordThenSources(emit)
    }

    /** The value the edge routes on: `IncomingOutput` = builder type argument 0 (the source's output). */
    private fun FirFunctionCall.subjectType(): ConeKotlinType? =
        resolvedType.typeArguments.getOrNull(0)?.type

    /** Classifies a fan-out subject type into a [EdgeDomain] (the §2.9 domain classifier). */
    @OptIn(SymbolInternals::class) // reads enum entries / sealed inheritors off the resolved class
    private fun classifyDomain(subject: ConeKotlinType, session: FirSession): EdgeDomain {
        val nullable = subject.isMarkedNullable
        if (subject.classId == StandardClassIds.Boolean) {
            return EdgeDomain.Enumerable(booleanCases(nullable), "Boolean")
        }
        val symbol = subject.toRegularClassSymbol(session) ?: return EdgeDomain.NonEnumerable
        if (symbol.classKind == ClassKind.ENUM_CLASS) {
            val entries = symbol.fir.declarations.filterIsInstance<FirEnumEntry>().map { it.name.asString() }
            if (entries.isEmpty()) return EdgeDomain.NonEnumerable
            val cases = entries.map { DomainCase(it, it) } + nullCase(nullable)
            return EdgeDomain.Enumerable(cases, "enum ${symbol.classId.shortClassName.asString()}")
        }
        val inheritors = getSealedClassInheritors(symbol.fir, session)
        if (inheritors.isEmpty()) return EdgeDomain.NonEnumerable
        val cases = inheritors.map { DomainCase(it.asFqNameString(), "is ${it.shortClassName.asString()}") } + nullCase(nullable)
        return EdgeDomain.Enumerable(cases, symbol.classId.shortClassName.asString())
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
    private fun extractCoverage(builder: FirFunctionCall): EdgeCoverage {
        val operators = mutableListOf<FirFunctionCall>()
        var current: FirExpression? = builder
        while (current is FirFunctionCall && current.calleeReference.name != FORWARD_TO) {
            operators.add(current)
            current = current.explicitReceiver
        }
        if (current !is FirFunctionCall) return EdgeCoverage.Opaque // no forwardTo — shape not understood
        val conditionOps = operators.filter { it.calleeReference.name.asString().startsWith("on") }
        if (conditionOps.isEmpty()) return EdgeCoverage.CatchAll // bare forwardTo, possibly with transformed
        if (operators.size > 1) return EdgeCoverage.Opaque // extra operators ⇒ not a clean discriminator
        val op = conditionOps.single()
        return when (op.calleeReference.name.asString()) {
            "onCondition" -> coverageFromOnCondition(op)
            "onIsInstance" -> coverageFromOnIsInstance(op)
            else -> EdgeCoverage.Opaque
        }
    }

    private fun coverageFromOnCondition(op: FirFunctionCall): EdgeCoverage {
        val lambda = op.arguments.lastOrNull() as? FirAnonymousFunctionExpression ?: return EdgeCoverage.Opaque
        val expr = lambda.anonymousFunction.body?.singleMeaningfulExpression() ?: return EdgeCoverage.Opaque
        if (expr is FirLiteralExpression && expr.value == true) return EdgeCoverage.CatchAll
        val eq = expr as? FirEqualityOperatorCall ?: return EdgeCoverage.Opaque
        if (eq.operation != FirOperation.EQ) return EdgeCoverage.Opaque
        return eq.argumentList.arguments.firstNotNullOfOrNull(::constantOperandCoverage) ?: EdgeCoverage.Opaque
    }

    /** Coverage from a `== <constant>` operand: a boolean/`null` literal or an enum entry; else null. */
    private fun constantOperandCoverage(operand: FirExpression): EdgeCoverage? = when {
        operand is FirLiteralExpression && operand.value == true -> EdgeCoverage.Covers(listOf("true"))
        operand is FirLiteralExpression && operand.value == false -> EdgeCoverage.Covers(listOf("false"))
        operand is FirLiteralExpression && operand.kind is ConstantValueKind.Null -> EdgeCoverage.Covers(listOf("null"))
        else -> (operand as? FirPropertyAccessExpression)?.calleeReference?.toResolvedEnumEntrySymbol()
            ?.let { EdgeCoverage.Covers(listOf(it.name.asString())) }
    }

    private fun coverageFromOnIsInstance(op: FirFunctionCall): EdgeCoverage {
        val classId = op.isInstanceTargetType()?.classId ?: return EdgeCoverage.Opaque
        return EdgeCoverage.Covers(listOf(classId.asFqNameString()))
    }

    /** The `T` of `onIsInstance(T::class)` (class-literal value arg) or `onIsInstance<T>()` (type arg). */
    private fun FirFunctionCall.isInstanceTargetType(): ConeKotlinType? {
        (arguments.firstOrNull() as? FirGetClassCall)?.let { return it.argument.resolvedType }
        return (typeArguments.firstOrNull() as? FirTypeProjectionWithVariance)?.typeRef?.coneType
    }

    /** The single expression a one-liner lambda body holds (unwrapping an implicit `return`), else null. */
    private fun FirBlock.singleMeaningfulExpression(): FirExpression? =
        when (val statement = statements.singleOrNull()) {
            is FirReturnExpression -> statement.result
            is FirExpression -> statement
            else -> null
        }
}
