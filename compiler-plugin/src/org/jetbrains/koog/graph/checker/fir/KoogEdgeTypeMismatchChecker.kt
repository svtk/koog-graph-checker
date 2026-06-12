package org.jetbrains.koog.graph.checker.fir

import org.jetbrains.koog.graph.checker.common.edgeMismatchOrigin
import org.jetbrains.koog.graph.checker.common.edgeMismatchSubject
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Reports [KoogDiagnostics.KOOG_EDGE_TYPE_MISMATCH] when a Koog strategy edge feeds the target node
 * a value of the wrong type.
 *
 * Koog encodes the rule in the type of the `edge(...)` argument,
 * `AIAgentEdgeBuilderIntermediate<IncomingOutput, IntermediateOutput, OutgoingInput>`: the edge is
 * valid iff `IntermediateOutput` (the value type reaching the target, after any operators) is a
 * subtype of `OutgoingInput` (the target node's input). This mirrors Koog's own `edge` bound
 * `CompatibleOutput : OutgoingInput`.
 *
 * The check is type-driven and never matches operator names, so it works for any chain operator —
 * `transformed`, `onCondition`, `onToolCall`, `onIsInstance`, user-defined extensions, etc. The two
 * compared types are read straight off the argument's resolved builder type. When the edge is
 * invalid the `edge` call fails to resolve (`NONE_APPLICABLE`) and the compiler leaves the outermost
 * call's own freshly-inferred type parameter unsubstituted in exactly one of those two slots; that
 * slot is recovered from the outermost call itself — `forwardTo`'s type argument for `OutgoingInput`,
 * the transformation lambda's result type for `IntermediateOutput`.
 *
 * The diagnostic message names the source/target nodes, anchors the highlight on the operand at
 * fault, and appends a remediation hint. The two types are passed as raw [ConeKotlinType]s so the
 * diagnostic renderer fills them with `RENDER_TYPE` (which adaptively disambiguates same-named
 * types); only the prose is built here, via the shared `edgeMismatchSubject`/`edgeMismatchOrigin`.
 */
object KoogEdgeTypeMismatchChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    private val EDGE = Name.identifier("edge")
    private val FORWARD_TO = Name.identifier("forwardTo")
    private val EDGE_BUILDER_CLASS_ID = ClassId(
        FqName("ai.koog.agents.core.dsl.builder"),
        Name.identifier("AIAgentEdgeBuilderIntermediate"),
    )

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (expression.calleeReference.name != EDGE) return

        val argument = expression.arguments.firstOrNull() ?: return
        val builderType = argument.resolvedType
        if (builderType.classId != EDGE_BUILDER_CLASS_ID) return

        val intermediateOutput = builderType.intermediateOutput(argument) ?: return
        val outgoingInput = builderType.outgoingInput(argument) ?: return

        if (intermediateOutput.isSubtypeOf(outgoingInput, context.session)) return

        val incoming = builderType.typeArgument(0).concreteOrNull()
        val forwardTo = (argument as? FirFunctionCall)?.findForwardToCall()
        val isBare = (argument as? FirFunctionCall)?.calleeReference?.name == FORWARD_TO

        val anchor = anchor(argument, forwardTo, isBare)
            ?: expression.calleeReference.source
            ?: expression.source

        reporter.reportOn(
            anchor,
            KoogDiagnostics.KOOG_EDGE_TYPE_MISMATCH,
            edgeMismatchSubject(
                sourceNode = forwardTo?.explicitReceiver?.simpleNameOrNull(),
                targetNode = forwardTo?.arguments?.firstOrNull()?.simpleNameOrNull(),
            ),
            edgeMismatchOrigin(afterTransform = incoming != null && incoming.changedTo(intermediateOutput)),
            intermediateOutput,
            outgoingInput,
            context,
        )
    }

    /** Whether an operator changed the value type (used only to choose the §1.4 wording). */
    private fun ConeKotlinType.changedTo(other: ConeKotlinType): Boolean = renderReadable() != other.renderReadable()

    /**
     * §1.2 — anchor the highlight on the operand at fault: the target node reference for a bare
     * `forwardTo`, otherwise the outermost operator (`transformed`/`onCondition`/`onIsInstance`/…).
     */
    private fun anchor(argument: FirExpression, forwardTo: FirFunctionCall?, isBare: Boolean): KtSourceElement? =
        if (isBare) forwardTo?.arguments?.firstOrNull()?.source
        else (argument as? FirFunctionCall)?.calleeReference?.source

    /** Walks the explicit-receiver chain to the innermost `forwardTo` call (`source forwardTo target`). */
    private fun FirFunctionCall.findForwardToCall(): FirFunctionCall? {
        var current: FirExpression? = this
        while (current is FirFunctionCall) {
            if (current.calleeReference.name == FORWARD_TO) return current
            current = current.explicitReceiver
        }
        return null
    }

    /** The `val`/property name of a bare node reference (e.g. `source`), or null for anything else. */
    private fun FirExpression.simpleNameOrNull(): String? {
        val access = this as? FirPropertyAccessExpression ?: return null
        if (access.explicitReceiver != null) return null
        return access.calleeReference.name.asString()
    }

    /**
     * `IntermediateOutput` = builder type argument 1. When the outermost operator deferred its
     * freshly-inferred result type to the failed `edge` call, recover it from that operator's
     * transformation lambda's result type.
     */
    private fun ConeKotlinType.intermediateOutput(argument: FirExpression): ConeKotlinType? =
        typeArgument(1).concreteOrNull() ?: (argument as? FirFunctionCall)?.lambdaResultType()

    /**
     * `OutgoingInput` = builder type argument 2. When a bare `forwardTo` deferred it to the failed
     * `edge` call, recover it from `forwardTo`'s own type argument.
     */
    private fun ConeKotlinType.outgoingInput(argument: FirExpression): ConeKotlinType? =
        typeArgument(2).concreteOrNull() ?: (argument as? FirFunctionCall)?.typeArgument(0).concreteOrNull()

    private fun ConeKotlinType.typeArgument(index: Int): ConeKotlinType? =
        typeArguments.getOrNull(index)?.type

    private fun ConeKotlinType?.concreteOrNull(): ConeKotlinType? =
        this?.takeUnless { it is ConeTypeParameterType }

    private fun FirFunctionCall.typeArgument(index: Int): ConeKotlinType? =
        (typeArguments.getOrNull(index) as? FirTypeProjectionWithVariance)?.typeRef?.coneType

    private fun FirFunctionCall.lambdaResultType(): ConeKotlinType? =
        arguments.firstNotNullOfOrNull {
            (it as? FirAnonymousFunctionExpression)?.anonymousFunction?.returnTypeRef?.coneTypeOrNull
        }.concreteOrNull()
}
