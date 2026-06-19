// RUN_PIPELINE_TILL: BACKEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

enum class Status { APPROVED, REJECTED }

// All enum entries are covered — no warning expected.
val strategy = strategy<String, String>("test") {
    val decide by node<String, Status> { Status.APPROVED }
    val yes by node<Status, String> { "approved" }
    val no by node<Status, String> { "rejected" }

    edge(nodeStart forwardTo decide)
    edge(decide forwardTo yes onCondition { it == Status.APPROVED })
    edge(decide forwardTo no onCondition { it == Status.REJECTED })
    edge(yes forwardTo nodeFinish)
    edge(no forwardTo nodeFinish)
}
