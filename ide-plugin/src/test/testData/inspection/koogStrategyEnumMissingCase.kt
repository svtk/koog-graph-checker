import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

enum class Route { SEARCH, ANSWER, ESCALATE }

val strategy = strategy<String, String>("test") {
    val classify by node<String, Route> { Route.SEARCH }
    val search by node<Route, String> { input -> input.name }
    val answer by node<Route, String> { input -> input.name }

    edge(nodeStart forwardTo classify)
    edge(<warning descr="'classify' routes on enum Route but no edge handles: ESCALATE. When 'classify' produces ESCALATE no edge matches and the run stalls. Add an edge for it, or an unconditional fallback edge from 'classify'.">classify</warning> forwardTo search onCondition { it == Route.SEARCH })
    edge(classify forwardTo answer onCondition { it == Route.ANSWER })
    edge(search forwardTo nodeFinish)
    edge(answer forwardTo nodeFinish)
}
