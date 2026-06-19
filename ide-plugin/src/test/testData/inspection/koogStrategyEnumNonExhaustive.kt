import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

enum class Route { SEARCH, ANSWER, ESCALATE }

val strategy = strategy<String, String>("test") {
    val <warning descr="'classify' routes on enum Route but no edge handles: ESCALATE. Add an edge for the missing case, or an unconditional fallback edge from 'classify'.">classify</warning> by node<String, Route> { Route.SEARCH }
    val searchNode by node<Route, String> { it.name }
    val answerNode by node<Route, String> { it.name }

    edge(nodeStart forwardTo classify)
    edge(classify forwardTo searchNode onCondition { it == Route.SEARCH })
    edge(classify forwardTo answerNode onCondition { it == Route.ANSWER })
    edge(searchNode forwardTo nodeFinish)
    edge(answerNode forwardTo nodeFinish)
}
