// RUN_PIPELINE_TILL: BACKEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// The conditional edge is ordered after an unconditional edge from 'a'.
val strategy = strategy<String, String>("test") {
    val a by node<String, String> { input -> input }

    edge(nodeStart forwardTo a)
    edge(a forwardTo nodeFinish)
    <!KOOG_SHADOWED_EDGE!>edge<!>(a forwardTo nodeFinish onCondition { true })
}
