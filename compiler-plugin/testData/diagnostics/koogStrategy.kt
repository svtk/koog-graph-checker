// RUN_PIPELINE_TILL: BACKEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val source by node<String, Int> { input -> input.length }
    val target by node<Int, String> { input -> input.toString() }

    edge(nodeStart forwardTo source)
    edge(source forwardTo target)
    edge(target forwardTo nodeFinish)
}
