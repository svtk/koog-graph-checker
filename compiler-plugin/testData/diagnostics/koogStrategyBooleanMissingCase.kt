// RUN_PIPELINE_TILL: BACKEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// §2.9 — the same engine over a Boolean domain: only the `true` case is routed, so `false` stalls.
val strategy = strategy<String, String>("test") {
    val decide by node<String, Boolean> { input -> input.isNotEmpty() }
    val yes by node<Boolean, String> { input -> input.toString() }

    edge(nodeStart forwardTo decide)
    edge(<!KOOG_MISSING_EDGE_CASES!>decide<!> forwardTo yes onCondition { it == true })
    edge(yes forwardTo nodeFinish)
}
