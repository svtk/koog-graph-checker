import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val first by node<String, String>("dup") { input -> input }
    val <error descr="Duplicate node name \"dup\" (already used by 'first'). Node names must be unique within a strategy/subgraph.">second</error> by node<String, String>("dup") { input -> input }

    edge(nodeStart forwardTo first)
    edge(first forwardTo second)
    edge(second forwardTo nodeFinish)
}
