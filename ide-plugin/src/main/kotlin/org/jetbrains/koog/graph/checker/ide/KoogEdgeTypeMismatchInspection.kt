@file:OptIn(KaExperimentalApi::class)

package org.jetbrains.koog.graph.checker.ide

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.koog.graph.checker.common.buildEdgeTypeMismatchMessage
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
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
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
 *
 * The message names the source/target nodes, anchors the highlight on the operand at fault, and
 * appends a remediation hint (see [buildEdgeTypeMismatchMessage]), matching the compiler plugin.
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

                    val renderer = KaTypeRendererForSource.WITH_SHORT_NAMES
                    val fromType = intermediateOutput.render(renderer, Variance.INVARIANT)
                    val toType = outgoingInput.render(renderer, Variance.INVARIANT)
                    val incomingType = typeArgAt(builderType, 0)?.render(renderer, Variance.INVARIANT)

                    val forwardTo = argument.findForwardToExpression()
                    val isBare = (argument as? KtBinaryExpression)?.operationReference?.text == "forwardTo"

                    val message = buildEdgeTypeMismatchMessage(
                        sourceNode = (forwardTo?.left as? KtNameReferenceExpression)?.text,
                        targetNode = (forwardTo?.right as? KtNameReferenceExpression)?.text,
                        fromType = fromType,
                        toType = toType,
                        afterTransform = incomingType != null && incomingType != fromType,
                    )

                    val anchor = anchor(argument, forwardTo, isBare) ?: expression.calleeExpression ?: expression

                    holder.registerProblem(anchor, message, ProblemHighlightType.GENERIC_ERROR)
                }
            }
        }

    /**
     * §1.2 — anchor the highlight on the operand at fault: the target node reference for a bare
     * `forwardTo`, otherwise the outermost operator (`transformed`/`onCondition`/`onIsInstance`/…).
     */
    private fun anchor(argument: KtExpression, forwardTo: KtBinaryExpression?, isBare: Boolean): PsiElement? =
        if (isBare) forwardTo?.right
        else (argument as? KtBinaryExpression)?.operationReference

    /** Descends the left operand chain to the innermost `forwardTo` infix call (`source forwardTo target`). */
    private fun KtExpression.findForwardToExpression(): KtBinaryExpression? {
        var current: KtExpression? = this
        while (current is KtBinaryExpression) {
            if (current.operationReference.text == "forwardTo") return current
            current = current.left
        }
        return null
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