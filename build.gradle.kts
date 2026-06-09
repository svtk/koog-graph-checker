plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.buildconfig) apply false
}

allprojects {
    group = "org.jetbrains.koog.graph.checker"
    version = "0.1.0-SNAPSHOT"
}
