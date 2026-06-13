// RUN_PIPELINE_TILL: FRONTEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// The finish node cannot have outgoing edges.
val strategy = strategy<String, String>("test") {
    val a by node<String, String> { input -> input }

    edge(nodeStart forwardTo a)
    edge(a forwardTo nodeFinish)
    edge(<!KOOG_FINISH_OUTGOING_EDGE!>nodeFinish<!> forwardTo a)
}
