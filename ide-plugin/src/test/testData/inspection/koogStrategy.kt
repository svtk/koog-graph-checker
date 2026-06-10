// No errors: edge types are compatible.
import ai.koog.agents.core.dsl.builder.*

val source = node<String, Int> { input -> input.length }
val target = node<Int, String> { input -> input.toString() }

fun test() {
    edge(source forwardTo target)
}
