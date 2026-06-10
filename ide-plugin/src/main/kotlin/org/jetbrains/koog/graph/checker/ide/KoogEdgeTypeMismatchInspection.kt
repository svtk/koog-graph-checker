@file:OptIn(KaExperimentalApi::class)

package org.jetbrains.koog.graph.checker.ide

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeArgumentWithVariance
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.types.Variance

/**
 * Reports a problem when a Koog strategy edge feeds the target node a value of the wrong type.
 *
 * Mirrors the compiler plugin's KoogEdgeTypeMismatchChecker but runs in the IDE using the
 * Kotlin Analysis API, so errors appear live without compilation.
 *
 * The check is type-driven: it reads IntermediateOutput (type arg 1) and OutgoingInput (type arg 2)
 * from the AIAgentEdgeBuilderIntermediate argument and verifies IntermediateOutput : OutgoingInput.
 * If either slot holds an unresolved type parameter (can happen during error recovery), fallback
 * recovery mirrors the compiler plugin: lambda return type for IntermediateOutput, receiver's
 * type arg for OutgoingInput.
 */
class KoogEdgeTypeMismatchInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : KtVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                if (expression.calleeExpression?.text != "edge") return

                val argument = expression.valueArguments
                    .firstOrNull()
                    ?.getArgumentExpression() ?: return

                analyze(expression) {
                    val builderType = argument.expressionType as? KaClassType ?: return@analyze
                    if (builderType.classId != EDGE_BUILDER_CLASS_ID) return@analyze

                    val intermediateOutput = intermediateOutput(builderType, argument) ?: return@analyze
                    val outgoingInput = outgoingInput(builderType, argument) ?: return@analyze

                    if (intermediateOutput.isSubtypeOf(outgoingInput)) return@analyze

                    val message = "Invalid edge: the edge's output type " +
                        "${intermediateOutput.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)} " +
                        "does not match the target node's input type " +
                        "${outgoingInput.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)}."

                    holder.registerProblem(
                        expression.calleeExpression ?: expression,
                        message,
                    )
                }
            }
        }

    // IntermediateOutput = type arg [1]. Fallback: lambda return type of the outermost transform.
    private fun KaSession.intermediateOutput(builderType: KaClassType, argument: KtExpression): KaType? =
        typeArgAt(builderType, 1) ?: lambdaResultType(argument)

    // OutgoingInput = type arg [2]. Fallback: type arg [2] of the receiver in a chained call.
    private fun KaSession.outgoingInput(builderType: KaClassType, argument: KtExpression): KaType? =
        typeArgAt(builderType, 2) ?: outgoingInputFallback(argument)

    private fun KaSession.typeArgAt(type: KaClassType, index: Int): KaType? =
        (type.typeArguments.getOrNull(index) as? KaTypeArgumentWithVariance)?.type
            ?.takeUnless { it is KaTypeParameterType }

    /**
     * Recovers IntermediateOutput from the first lambda argument found in the outermost call.
     * Handles both "expr.transformed { }" (dot-qualified) and plain call expressions.
     */
    private fun KaSession.lambdaResultType(argument: KtExpression): KaType? {
        val call = when (argument) {
            is KtDotQualifiedExpression -> argument.selectorExpression as? KtCallExpression
            is KtCallExpression -> argument
            else -> null
        } ?: return null
        val lambda = call.valueArguments
            .mapNotNull { it.getArgumentExpression() }
            .filterIsInstance<KtLambdaExpression>()
            .firstOrNull() ?: return null
        return (lambda.expressionType as? KaFunctionType)?.returnType
            ?.takeUnless { it is KaTypeParameterType }
    }

    /**
     * Recovers OutgoingInput from the receiver's type arg [2] in a chained call.
     * Applies when the argument is "receiver.transform { }" and the receiver already
     * carries a resolved OutgoingInput.
     */
    private fun KaSession.outgoingInputFallback(argument: KtExpression): KaType? {
        val receiver = (argument as? KtDotQualifiedExpression)?.receiverExpression ?: return null
        val receiverType = receiver.expressionType as? KaClassType ?: return null
        if (receiverType.classId != EDGE_BUILDER_CLASS_ID) return null
        return typeArgAt(receiverType, 2)
    }

}

private val EDGE_BUILDER_CLASS_ID = ClassId(
    FqName("ai.koog.agents.core.dsl.builder"),
    Name.identifier("AIAgentEdgeBuilderIntermediate"),
)