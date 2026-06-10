import ai.koog.agents.core.dsl.builder.*

val source = node<String, Int> { input -> input.length }
val target = node<String, String> { input -> input }

fun test() {
    <error descr="Invalid edge: the edge's output type Long does not match the target node's input type String.">edge</error>(source forwardTo target transformed { it.toLong() })
}
