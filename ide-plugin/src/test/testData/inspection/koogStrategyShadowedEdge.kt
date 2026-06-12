import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy

val strategy = strategy<String, String>("test") {
    val a by node<String, String> { input -> input }

    edge(nodeStart forwardTo a)
    edge(a forwardTo nodeFinish)
    <warning descr="This edge can never be taken: an earlier unconditional edge from 'a' always matches first. Reorder so conditional edges precede the unconditional one.">edge(a forwardTo nodeFinish onCondition { true })</warning>
}
