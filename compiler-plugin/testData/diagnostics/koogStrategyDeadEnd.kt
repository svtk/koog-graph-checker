// RUN_PIPELINE_TILL: BACKEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// 'process' is reachable but has no outgoing edge.
val strategy = strategy<String, String>("test") {
    val a by node<String, String> { input -> input }
    val <!KOOG_DEAD_END_NODE!>process<!> by node<String, String> { input -> input }

    edge(nodeStart forwardTo a)
    edge(a forwardTo nodeFinish onCondition { true })
    edge(a forwardTo process)
}
