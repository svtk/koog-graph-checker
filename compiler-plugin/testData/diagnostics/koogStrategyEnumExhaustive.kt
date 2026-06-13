// RUN_PIPELINE_TILL: BACKEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// §2.8/§2.9 — every entry of the enum is handled, so the fan-out is exhaustive and nothing is reported
// (proves the check does not false-alarm on a complete enum routing).
enum class Decision { YES, NO }

val strategy = strategy<String, String>("test") {
    val classify by node<String, Decision> { Decision.YES }
    val yes by node<Decision, String> { input -> input.name }
    val no by node<Decision, String> { input -> input.name }

    edge(nodeStart forwardTo classify)
    edge(classify forwardTo yes onCondition { it == Decision.YES })
    edge(classify forwardTo no onCondition { it == Decision.NO })
    edge(yes forwardTo nodeFinish)
    edge(no forwardTo nodeFinish)
}
