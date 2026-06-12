import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("named") {
    val classify by node<String, Int> { input -> input.length }
    val summarize by node<String, String> { input -> input }

    edge(nodeStart forwardTo classify)
    edge(classify forwardTo <error descr="Invalid edge from node 'classify' to node 'summarize': the source node's output type Int does not match the target node's input type String. Insert `transformed { }` to convert Int to String, or change the target node's input type.">summarize</error>)
    edge(summarize forwardTo nodeFinish)
}
