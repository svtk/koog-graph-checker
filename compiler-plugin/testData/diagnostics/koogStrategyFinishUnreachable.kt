// RUN_PIPELINE_TILL: FRONTEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// No path reaches nodeFinish (the a/b loop never terminates).
val strategy = <!KOOG_FINISH_UNREACHABLE!>strategy<!><String, String>("pipeline") {
    val a by node<String, Int> { input -> input.length }
    val b by node<Int, String> { input -> input.toString() }

    edge(nodeStart forwardTo a)
    edge(a forwardTo b)
    edge(b forwardTo a)
}
