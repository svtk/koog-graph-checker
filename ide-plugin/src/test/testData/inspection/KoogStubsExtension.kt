// Stubs for extension package used by the onIsInstance test.

package ai.koog.agents.core.dsl.extension

import ai.koog.agents.core.dsl.builder.AIAgentEdgeBuilderIntermediate
import kotlin.reflect.KClass

infix fun <IncomingOutput, IntermediateOutput, SpecificOutput : IntermediateOutput, OutgoingInput>
    AIAgentEdgeBuilderIntermediate<IncomingOutput, IntermediateOutput, OutgoingInput>.onIsInstance(
    cls: KClass<SpecificOutput>,
): AIAgentEdgeBuilderIntermediate<IncomingOutput, SpecificOutput, OutgoingInput> = TODO()
