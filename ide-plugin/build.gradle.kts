import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

val koogClasspath by configurations.dependencyScope("koogClasspath")
val koogResolvable by configurations.resolvable("koogResolvable") {
    extendsFrom(koogClasspath)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    koogClasspath("ai.koog:koog-agents:1.0.0")

    // Shared error-message builder, so the compiler and IDE diagnostics stay byte-identical.
    implementation(project(":common"))

    intellijPlatform {
        intellijIdea("2026.1.3")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
    }
}

tasks.named("instrumentTestCode") {
    enabled = false
}

tasks.test {
    systemProperty("koog.classpath", koogResolvable.asPath)
}
