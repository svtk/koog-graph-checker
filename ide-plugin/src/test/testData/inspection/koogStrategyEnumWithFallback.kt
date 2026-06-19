import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

enum class Action { RUN, STOP, PAUSE }

val strategy = strategy<String, String>("test") {
    val decide by node<String, Action> { Action.RUN }
    val run by node<Action, String> { "running" }
    val fallback by node<Action, String> { "other" }

    edge(nodeStart forwardTo decide)
    edge(decide forwardTo run onCondition { it == Action.RUN })
    edge(decide forwardTo fallback)
    edge(run forwardTo nodeFinish)
    edge(fallback forwardTo nodeFinish)
}
