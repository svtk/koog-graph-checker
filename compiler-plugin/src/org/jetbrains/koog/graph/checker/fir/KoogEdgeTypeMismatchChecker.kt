package org.jetbrains.koog.graph.checker.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isSubtypeOf
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
 */
object KoogEdgeTypeMismatchChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    private val EDGE = Name.identifier("edge")
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

        reporter.reportOn(
            expression.calleeReference.source ?: expression.source,
            KoogDiagnostics.KOOG_EDGE_TYPE_MISMATCH,
            intermediateOutput,
            outgoingInput,
            context,
        )
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
