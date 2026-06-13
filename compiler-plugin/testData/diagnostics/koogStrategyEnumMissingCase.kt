// RUN_PIPELINE_TILL: BACKEND

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

// §2.8/§2.9 — 'classify' routes on the enum Route, but the edges cover only SEARCH and ANSWER, so a
// run that produces ESCALATE finds no matching edge and stalls. Reported on the source node reference.
enum class Route { SEARCH, ANSWER, ESCALATE }

val strategy = strategy<String, String>("test") {
    val classify by node<String, Route> { Route.SEARCH }
    val search by node<Route, String> { input -> input.name }
    val answer by node<Route, String> { input -> input.name }

    edge(nodeStart forwardTo classify)
    edge(<!KOOG_MISSING_EDGE_CASES!>classify<!> forwardTo search onCondition { it == Route.SEARCH })
    edge(classify forwardTo answer onCondition { it == Route.ANSWER })
    edge(search forwardTo nodeFinish)
    edge(answer forwardTo nodeFinish)
}
