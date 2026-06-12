import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val source by node<String, Int> { input -> input.length }
    val target by node<String, String> { input -> input }

    edge(nodeStart forwardTo source)
    edge(source forwardTo target onCondition { it > 0 } <error descr="Invalid edge from node 'source' to node 'target': the value type after the transform Long does not match the target node's input type String. Insert `transformed { }` to convert Long to String, or change the target node's input type.">transformed</error> { it.toLong() })
    edge(target forwardTo nodeFinish)
}
