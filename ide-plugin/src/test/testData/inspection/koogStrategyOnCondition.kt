import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val source by node<String, Int> { input -> input.length }
    val target by node<String, String> { input -> input }

    edge(nodeStart forwardTo source)
    edge(source forwardTo target <error descr="Invalid edge from node 'source' to node 'target': the source node's output type Int does not match the target node's input type String. Insert `transformed { }` to convert Int to String, or change the target node's input type.">onCondition</error> { it > 0 })
    edge(target forwardTo nodeFinish)
}
