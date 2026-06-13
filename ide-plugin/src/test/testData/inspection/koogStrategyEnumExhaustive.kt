import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

enum class Decision { YES, NO }

val strategy = strategy<String, String>("test") {
    val classify by node<String, Decision> { Decision.YES }
    val yes by node<Decision, String> { input -> input.name }
    val no by node<Decision, String> { input -> input.name }

    edge(nodeStart forwardTo classify)
    edge(classify forwardTo yes onCondition { it == Decision.YES })
    edge(classify forwardTo no onCondition { it == Decision.NO })
    edge(yes forwardTo nodeFinish)
    edge(no forwardTo nodeFinish)
}
