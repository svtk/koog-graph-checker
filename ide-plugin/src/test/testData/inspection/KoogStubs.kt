// Minimal stubs for ai.koog types used by the inspection tests.
// These replicate the signatures the inspection logic depends on.

package ai.koog.agents.core.dsl.builder

class AIAgentNode<Input, Output>

class AIAgentEdgeBuilderIntermediate<IncomingOutput, IntermediateOutput, OutgoingInput>

infix fun <SourceOutput, TargetInput> AIAgentNode<*, SourceOutput>.forwardTo(
    target: AIAgentNode<TargetInput, *>
): AIAgentEdgeBuilderIntermediate<SourceOutput, SourceOutput, TargetInput> = TODO()

fun <IncomingOutput, CompatibleOutput : OutgoingInput, OutgoingInput>
    edge(edge: AIAgentEdgeBuilderIntermediate<IncomingOutput, CompatibleOutput, OutgoingInput>): Unit = TODO()

fun <Input, Output> node(block: (Input) -> Output): AIAgentNode<Input, Output> = TODO()

infix fun <IncomingOutput, OldIntermediate, NewIntermediate, OutgoingInput>
    AIAgentEdgeBuilderIntermediate<IncomingOutput, OldIntermediate, OutgoingInput>.transformed(
    block: (OldIntermediate) -> NewIntermediate,
): AIAgentEdgeBuilderIntermediate<IncomingOutput, NewIntermediate, OutgoingInput> = TODO()

infix fun <IncomingOutput, IntermediateOutput, OutgoingInput>
    AIAgentEdgeBuilderIntermediate<IncomingOutput, IntermediateOutput, OutgoingInput>.onCondition(
    condition: (IntermediateOutput) -> Boolean,
): AIAgentEdgeBuilderIntermediate<IncomingOutput, IntermediateOutput, OutgoingInput> = TODO()
