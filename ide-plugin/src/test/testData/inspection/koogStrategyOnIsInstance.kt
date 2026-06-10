// Error: onIsInstance narrows to Int, target expects String.
import ai.koog.agents.core.dsl.builder.*
import ai.koog.agents.core.dsl.extension.onIsInstance

val source = node<String, Any> { input -> input }
val target = node<String, String> { input -> input }

fun test() {
    edge(source forwardTo target onIsInstance Int::class)
}
