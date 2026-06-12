import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.onIsInstance

val strategy = strategy<String, String>("test") {
    val source by node<String, Any> { input -> input }
    val target by node<String, String> { input -> input }

    edge(nodeStart forwardTo source)
    edge(source forwardTo target <error descr="Invalid edge from node 'source' to node 'target': the value type after the transform Int does not match the target node's input type String. Insert `transformed { }` to convert Int to String, or change the target node's input type.">onIsInstance</error> Int::class)
    edge(target forwardTo nodeFinish)
}
