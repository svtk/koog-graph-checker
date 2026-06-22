// RUN_PIPELINE_TILL: BACKEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// 'orphan' has no incoming edge, so nodeStart never reaches it.
val strategy = strategy<String, String>("test") {
    val a by node<String, String> { input -> input }
    val <!KOOG_UNREACHABLE_NODE!>orphan<!> by node<String, String> { input -> input }

    edge(nodeStart forwardTo a)
    edge(a forwardTo nodeFinish)
    edge(orphan forwardTo nodeFinish)
}
