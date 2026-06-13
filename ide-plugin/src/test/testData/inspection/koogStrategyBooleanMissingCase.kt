import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val decide by node<String, Boolean> { input -> input.isNotEmpty() }
    val yes by node<Boolean, String> { input -> input.toString() }

    edge(nodeStart forwardTo decide)
    edge(<warning descr="'decide' routes on Boolean but no edge handles: false. When 'decide' produces false no edge matches and the run stalls. Add an edge for it, or an unconditional fallback edge from 'decide'.">decide</warning> forwardTo yes onCondition { it == true })
    edge(yes forwardTo nodeFinish)
}
