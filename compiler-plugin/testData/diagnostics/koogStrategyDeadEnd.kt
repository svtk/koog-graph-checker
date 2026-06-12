// RUN_PIPELINE_TILL: BACKEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// §2.6 — 'process' is reachable but has no outgoing edge.
val strategy = strategy<String, String>("test") {
    val a by node<String, String> { input -> input }
    <!KOOG_DEAD_END_NODE!>val process by node<String, String> { input -> input }<!>

    edge(nodeStart forwardTo a)
    edge(a forwardTo nodeFinish onCondition { true })
    edge(a forwardTo process)
}
