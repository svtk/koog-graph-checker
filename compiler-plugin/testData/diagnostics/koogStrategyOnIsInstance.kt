// RUN_PIPELINE_TILL: FRONTEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.onIsInstance

val strategy = strategy<String, String>("test") {
    val source by node<String, Any> { input -> input }
    val target by node<String, String> { input -> input }

    edge(nodeStart forwardTo source)
    <!KOOG_EDGE_TYPE_MISMATCH, NONE_APPLICABLE!>edge<!>(source forwardTo target onIsInstance Int::class)
    edge(target forwardTo nodeFinish)
}
