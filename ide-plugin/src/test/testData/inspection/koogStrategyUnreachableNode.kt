import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val a by node<String, String> { input -> input }
    val <warning descr="Node 'orphan' is never reached from 'nodeStart'; it will not execute. Add an incoming edge (e.g. edge(... forwardTo orphan)).">orphan</warning> by node<String, String> { input -> input }

    edge(nodeStart forwardTo a)
    edge(a forwardTo nodeFinish)
    edge(orphan forwardTo nodeFinish)
}
