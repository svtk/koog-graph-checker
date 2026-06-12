// RUN_PIPELINE_TILL: FRONTEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("named") {
    val classify by node<String, Int> { input -> input.length }
    val summarize by node<String, String> { input -> input }

    edge(nodeStart forwardTo classify)
    <!NONE_APPLICABLE!>edge<!>(classify forwardTo <!KOOG_EDGE_TYPE_MISMATCH!>summarize<!>)
    edge(summarize forwardTo nodeFinish)
}
