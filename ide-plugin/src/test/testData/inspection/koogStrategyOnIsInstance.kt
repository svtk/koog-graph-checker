import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.onIsInstance

val strategy = strategy<String, String>("test") {
    val source by node<String, Any> { input -> input }
    val target by node<String, String> { input -> input }

    edge(nodeStart forwardTo source)
    <error descr="Invalid edge: the edge's output type Int does not match the target node's input type String.">edge</error>(source forwardTo target onIsInstance Int::class)
    edge(target forwardTo nodeFinish)
}
