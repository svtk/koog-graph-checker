// Error: transform produces Long, target expects String.
import ai.koog.agents.core.dsl.builder.*

val source = node<String, Int> { input -> input.length }
val target = node<String, String> { input -> input }

fun test() {
    edge(source forwardTo target transformed { it.toLong() })
}
