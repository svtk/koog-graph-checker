import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val a by node<String, String> { input -> input }
    val <warning descr="'process' is reachable but has no outgoing edge; execution will stall here. Add an edge from 'process' (e.g. to 'nodeFinish').">process</warning> by node<String, String> { input -> input }

    edge(nodeStart forwardTo a)
    edge(a forwardTo nodeFinish onCondition { true })
    edge(a forwardTo process)
}
