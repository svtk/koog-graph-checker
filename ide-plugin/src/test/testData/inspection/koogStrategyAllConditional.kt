import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val <weak_warning descr="'router' has only conditional outgoing edges; inputs matching no condition will stall. Consider adding an unconditional fallback edge from 'router'.">router</weak_warning> by node<String, String> { input -> input }
    val a by node<String, String> { input -> input }
    val b by node<String, String> { input -> input }

    edge(nodeStart forwardTo router)
    edge(router forwardTo a onCondition { it.length > 10 })
    edge(router forwardTo b onCondition { it.length <= 5 })
    edge(a forwardTo nodeFinish)
    edge(b forwardTo nodeFinish)
}
