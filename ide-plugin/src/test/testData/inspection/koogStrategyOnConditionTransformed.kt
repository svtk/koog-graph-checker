import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val source by node<String, Int> { input -> input.length }
    val target by node<String, String> { input -> input }

    edge(nodeStart forwardTo source)
    <error descr="Invalid edge: the edge's output type Long does not match the target node's input type String.">edge</error>(source forwardTo target onCondition { it > 0 } transformed { it.toLong() })
    edge(target forwardTo nodeFinish)
}
