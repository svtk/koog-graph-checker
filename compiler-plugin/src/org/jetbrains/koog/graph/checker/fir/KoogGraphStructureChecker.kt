@file:OptIn(
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
    org.jetbrains.kotlin.fir.declarations.ResolveStateAccess::class,
)

package org.jetbrains.koog.graph.checker.fir

import org.jetbrains.koog.graph.checker.common.EdgeCondition
import org.jetbrains.koog.graph.checker.common.GraphEdge
import org.jetbrains.koog.graph.checker.common.GraphFinding
import org.jetbrains.koog.graph.checker.common.GraphFindingKind
import org.jetbrains.koog.graph.checker.common.GraphModel
import org.jetbrains.koog.graph.checker.common.GraphNode
import org.jetbrains.koog.graph.checker.common.NodeDomain
import org.jetbrains.koog.graph.checker.common.NodeKind
import org.jetbrains.koog.graph.checker.common.analyzeGraph
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.getSealedClassInheritors
import org.jetbrains.kotlin.fir.declarations.processAllDeclarations
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedDelegateExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isBoolean
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

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

        val model = buildModel(expression, context) ?: return
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
        GraphFindingKind.ALL_CONDITIONAL_NO_FALLBACK -> KoogDiagnostics.KOOG_ALL_CONDITIONAL_NO_FALLBACK
        GraphFindingKind.NON_EXHAUSTIVE_EDGE_CONDITIONS -> KoogDiagnostics.KOOG_NON_EXHAUSTIVE_EDGE_CONDITIONS
    }

    /** Reconstructs the block's graph from the trailing-lambda body. */
    private fun buildModel(call: FirFunctionCall, context: CheckerContext): GraphModel<KtSourceElement>? {
        val lambda = call.arguments.filterIsInstance<FirAnonymousFunctionExpression>().lastOrNull() ?: return null
        val body = lambda.anonymousFunction.body ?: return null
        val scopeAnchor = call.calleeReference.source ?: call.source ?: return null

        val startDomain = call.startNodeDomain(context.session)
        val nodes = mutableListOf(
            GraphNode<KtSourceElement>("nodeStart", "nodeStart", NodeKind.START, startDomain, null),
            GraphNode<KtSourceElement>("nodeFinish", "nodeFinish", NodeKind.FINISH, null, null),
        )
        val edges = mutableListOf<GraphEdge<KtSourceElement>>()

        for (statement in body.statements) {
            when {
                statement is FirProperty -> statement.toGraphNode(context.session)?.let(nodes::add)
                statement is FirFunctionCall && statement.calleeReference.name == EDGE ->
                    statement.toEdge()?.let(edges::add)
                statement is FirFunctionCall && statement.calleeReference.name == THEN ->
                    statement.toThenEdges(edges)
            }
        }

        return GraphModel(call.firstStringLiteralArg(), scopeAnchor, nodes, edges)
    }

    /** A `val x by node(...)` / `nodeLLMRequest(...)` / `subgraph(...) { }` declaration, or null. */
    private fun FirProperty.toGraphNode(session: FirSession): GraphNode<KtSourceElement>? {
        val delegateCall = delegate?.unwrapDelegateCall() ?: return null
        val delegateName = delegateCall.calleeReference.name.asString()
        val kind = when {
            delegateName == "subgraph" -> NodeKind.SUBGRAPH
            delegateName.startsWith("node") -> NodeKind.NODE
            else -> return null
        }
        val propertyName = name.asString()
        val source = source ?: return null
        val domain = outputTypeDomain(session)
        return GraphNode(propertyName, delegateCall.firstStringLiteralArg() ?: propertyName, kind, domain, source)
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
            condition = builder.extractEdgeCondition(),
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
                condition = EdgeCondition.CatchAll,
                edgeAnchor = anchor,
                sourceAnchor = sourceRef?.source,
            )
        }
        if (receiver is FirFunctionCall && receiver.calleeReference.name == THEN) receiver.toThenEdges(out)
    }

    // ── Edge condition extraction ──────────────────────────────────────────────

    /**
     * Semantically classifies the edge's condition for the exhaustiveness check.
     *
     * Walks the builder chain (outermost operator → `forwardTo`) collecting condition operators. If
     * exactly one is found and it is a recognized pure discriminator, returns its classification;
     * otherwise returns [EdgeCondition.Opaque] (or [EdgeCondition.CatchAll] for a bare `forwardTo`).
     */
    private fun FirFunctionCall.extractEdgeCondition(): EdgeCondition {
        var conditionCall: FirFunctionCall? = null
        var current: FirExpression? = this

        while (current is FirFunctionCall) {
            val name = current.calleeReference.name
            if (name == FORWARD_TO) break
            if (name.asString().startsWith("on")) {
                if (conditionCall != null) return EdgeCondition.Opaque
                conditionCall = current
            }
            current = current.explicitReceiver
        }

        if (conditionCall == null) return EdgeCondition.CatchAll

        return when (conditionCall.calleeReference.name.asString()) {
            "onCondition" -> analyzeOnConditionLambda(conditionCall)
            "onIsInstance" -> analyzeOnIsInstance(conditionCall)
            else -> EdgeCondition.Opaque
        }
    }

    /** Inspects the `onCondition` lambda body for a pure discriminator or catch-all. */
    private fun analyzeOnConditionLambda(call: FirFunctionCall): EdgeCondition {
        val lambdaExpr = call.arguments
            .filterIsInstance<FirAnonymousFunctionExpression>()
            .firstOrNull() ?: return EdgeCondition.Opaque
        val body = lambdaExpr.anonymousFunction.body ?: return EdgeCondition.Opaque
        val statement = body.statements.singleOrNull() ?: return EdgeCondition.Opaque
        val expr = if (statement is FirReturnExpression) statement.result else statement

        if (expr is FirLiteralExpression && expr.value == true) return EdgeCondition.CatchAll

        if (expr is FirEqualityOperatorCall && expr.operation == FirOperation.EQ) {
            val args = expr.argumentList.arguments
            if (args.size == 2) {
                return args[0].asDiscriminatorValue() ?: args[1].asDiscriminatorValue() ?: EdgeCondition.Opaque
            }
        }

        return EdgeCondition.Opaque
    }

    /** If this expression is an enum entry reference or a boolean literal, returns the corresponding discriminator. */
    private fun FirExpression.asDiscriminatorValue(): EdgeCondition? {
        if (this is FirLiteralExpression) {
            if (value is Boolean) return EdgeCondition.ValueMatch(value.toString())
        }

        if (this is FirPropertyAccessExpression) {
            val ref = calleeReference as? FirResolvedNamedReference ?: return null
            if (ref.resolvedSymbol is FirEnumEntrySymbol) {
                return EdgeCondition.ValueMatch(ref.name.asString())
            }
        }

        return null
    }

    /** Extracts the checked type name from an `onIsInstance(T::class)` call's type arguments. */
    private fun analyzeOnIsInstance(call: FirFunctionCall): EdgeCondition {
        val typeArgs = call.typeArguments
        if (typeArgs.size < 4) return EdgeCondition.Opaque
        val checkedType = (typeArgs[3] as? FirTypeProjectionWithVariance)?.typeRef?.coneType
            ?: return EdgeCondition.Opaque
        val checkedName = checkedType.classId?.shortClassName?.asString() ?: return EdgeCondition.Opaque
        return EdgeCondition.TypeCheck(checkedName)
    }

    // ── Node domain classification ─────────────────────────────────────────────

    /** Extracts the output type's domain from the property's resolved return type (second type arg of the node). */
    private fun FirProperty.outputTypeDomain(session: FirSession): NodeDomain? {
        val propertyType = returnTypeRef.coneTypeOrNull ?: return null
        val outputType = propertyType.typeArguments.getOrNull(1)?.type ?: return null
        return classifyDomain(outputType, session)
    }

    /** Extracts the domain of the strategy/subgraph's input type (first type arg = `nodeStart`'s output). */
    private fun FirFunctionCall.startNodeDomain(session: FirSession): NodeDomain? {
        val inputType = (typeArguments.getOrNull(0) as? FirTypeProjectionWithVariance)
            ?.typeRef?.coneType ?: return null
        return classifyDomain(inputType, session)
    }

    /** Classifies a resolved type into a [NodeDomain] for the exhaustiveness check. */
    private fun classifyDomain(type: ConeKotlinType, session: FirSession): NodeDomain {
        if (type.classId == StandardClassIds.Boolean) return NodeDomain.BooleanDomain

        val classId = type.classId ?: return NodeDomain.NonEnumerable
        val classSymbol = session.symbolProvider
            .getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
            ?: return NodeDomain.NonEnumerable

        if (classSymbol.classKind == ClassKind.ENUM_CLASS) {
            val entries = mutableListOf<String>()
            classSymbol.processAllDeclarations(session) { symbol ->
                if (symbol is FirEnumEntrySymbol) entries.add(symbol.name.asString())
            }
            return NodeDomain.EnumDomain(classSymbol.name.asString(), entries)
        }

        if (classSymbol.fir.status.modality == Modality.SEALED) {
            val sealedInheritors = classSymbol.fir.getSealedClassInheritors(session)
            if (sealedInheritors.isNotEmpty()) {
                val subtypeNames = sealedInheritors.map { it.shortClassName.asString() }
                return NodeDomain.SealedDomain(classSymbol.name.asString(), subtypeNames)
            }
        }

        return NodeDomain.NonEnumerable
    }

    // ── Shared helpers ─────────────────────────────────────────────────────────

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
