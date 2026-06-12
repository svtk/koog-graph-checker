// RUN_PIPELINE_TILL: FRONTEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// §2.3 — two nodes share the explicit name "dup".
val strategy = strategy<String, String>("test") {
    val first by node<String, String>("dup") { input -> input }
    <!KOOG_DUPLICATE_NODE_NAME!>val second by node<String, String>("dup") { input -> input }<!>

    edge(nodeStart forwardTo first)
    edge(first forwardTo second)
    edge(second forwardTo nodeFinish)
}
