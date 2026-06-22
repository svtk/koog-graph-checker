// RUN_PIPELINE_TILL: FRONTEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// Two nodes share the explicit name "dup".
val strategy = strategy<String, String>("test") {
    val first by node<String, String>("dup") { input -> input }
    val <!KOOG_DUPLICATE_NODE_NAME!>second<!> by node<String, String>("dup") { input -> input }

    edge(nodeStart forwardTo first)
    edge(first forwardTo second)
    edge(second forwardTo nodeFinish)
}
