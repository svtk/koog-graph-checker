import ai.koog.agents.core.dsl.builder.*
import ai.koog.agents.core.dsl.extension.onIsInstance

val source = node<String, Any> { input -> input }
val target = node<String, String> { input -> input }

fun test() {
    <error descr="Invalid edge: the edge's output type Int does not match the target node's input type String.">edge</error>(source forwardTo target onIsInstance Int::class)
}
