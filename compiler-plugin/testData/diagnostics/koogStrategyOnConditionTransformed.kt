// RUN_PIPELINE_TILL: FRONTEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    <!KOOG_ALL_CONDITIONAL_NO_FALLBACK!>val source by node<String, Int> { input -> input.length }<!>
    val target by node<String, String> { input -> input }

    edge(nodeStart forwardTo source)
    <!NONE_APPLICABLE!>edge<!>(source forwardTo target onCondition { it > 0 } <!KOOG_EDGE_TYPE_MISMATCH!>transformed<!> { it.toLong() })
    edge(target forwardTo nodeFinish)
}
