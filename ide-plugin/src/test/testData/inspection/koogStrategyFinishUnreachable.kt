import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = <error descr="'nodeFinish' is not reachable from 'nodeStart' in \"pipeline\". The graph cannot terminate; add an edge that eventually reaches 'nodeFinish'.">strategy</error><String, String>("pipeline") {
    val a by node<String, Int> { input -> input.length }
    val b by node<Int, String> { input -> input.toString() }

    edge(nodeStart forwardTo a)
    edge(a forwardTo b)
    edge(b forwardTo a)
}
