// RUN_PIPELINE_TILL: BACKEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// All outgoing edges from 'router' are conditional; no unconditional fallback.
val strategy = strategy<String, String>("test") {
    <!KOOG_ALL_CONDITIONAL_NO_FALLBACK!>val router by node<String, String> { input -> input }<!>
    val a by node<String, String> { input -> input }
    val b by node<String, String> { input -> input }

    edge(nodeStart forwardTo router)
    edge(router forwardTo a onCondition { it.length > 10 })
    edge(router forwardTo b onCondition { it.length <= 5 })
    edge(a forwardTo nodeFinish)
    edge(b forwardTo nodeFinish)
}
