import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val a by node<String, String> { input -> input }

    edge(nodeStart forwardTo a)
    edge(a forwardTo nodeFinish)
    edge(<error descr="'nodeFinish' cannot have outgoing edges — it is the terminal node of the graph.">nodeFinish</error> forwardTo a)
}
