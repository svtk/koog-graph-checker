// RUN_PIPELINE_TILL: FRONTEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val source by node<String, Int> { input -> input.length }
    val target by node<String, String> { input -> input }

    nodeStart then source
    source <!CANNOT_INFER_PARAMETER_TYPE!>then<!> <!ARGUMENT_TYPE_MISMATCH, KOOG_EDGE_TYPE_MISMATCH!>target<!>
    target then nodeFinish
}
