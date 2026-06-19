import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val <warning descr="'decide' routes on Boolean but no edge handles: false. Add an edge for the missing case, or an unconditional fallback edge from 'decide'.">decide</warning> by node<String, Boolean> { it.isNotEmpty() }
    val yes by node<Boolean, String> { "yes" }

    edge(nodeStart forwardTo decide)
    edge(decide forwardTo yes onCondition { it == true })
    edge(yes forwardTo nodeFinish)
}
