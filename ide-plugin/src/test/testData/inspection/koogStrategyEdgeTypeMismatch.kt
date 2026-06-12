import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val source by node<String, Int> { input -> input.length }
    val target by node<String, String> { input -> input }

    edge(nodeStart forwardTo source)
    edge(source forwardTo <error descr="Invalid edge from node 'source' to node 'target': the source node's output type Int does not match the target node's input type String. Insert `transformed { }` to convert Int to String, or change the target node's input type.">target</error>)
    edge(target forwardTo nodeFinish)
}
