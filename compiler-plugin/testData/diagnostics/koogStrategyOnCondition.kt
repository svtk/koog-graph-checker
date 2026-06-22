// RUN_PIPELINE_TILL: FRONTEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val <!KOOG_ALL_CONDITIONAL_NO_FALLBACK!>source<!> by node<String, Int> { input -> input.length }
    val target by node<String, String> { input -> input }

    edge(nodeStart forwardTo source)
    <!NONE_APPLICABLE!>edge<!>(source forwardTo target <!KOOG_EDGE_TYPE_MISMATCH!>onCondition<!> { it > 0 })
    edge(target forwardTo nodeFinish)
}
