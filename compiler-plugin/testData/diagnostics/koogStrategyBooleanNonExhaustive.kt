// RUN_PIPELINE_TILL: BACKEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// 'decide' routes on Boolean but no edge handles: false.
val strategy = strategy<String, String>("test") {
    val <!KOOG_NON_EXHAUSTIVE_EDGE_CONDITIONS!>decide<!> by node<String, Boolean> { it.isNotEmpty() }
    val yes by node<Boolean, String> { "yes" }

    edge(nodeStart forwardTo decide)
    edge(decide forwardTo yes onCondition { it == true })
    edge(yes forwardTo nodeFinish)
}
