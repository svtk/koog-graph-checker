import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

enum class Kind { A, B, C }

val strategy = strategy<String, String>("test") {
    val classify by node<String, Kind> { Kind.A }
    val handleA by node<Kind, String> { it.name }
    val handleOther by node<Kind, String> { it.name }

    edge(nodeStart forwardTo classify)
    edge(classify forwardTo handleA onCondition { it == Kind.A })
    edge(classify forwardTo handleOther onCondition { it.name.length > 1 })
    edge(handleA forwardTo nodeFinish)
    edge(handleOther forwardTo nodeFinish)
}
