plugins {
    kotlin("jvm") version "2.4.0"
}

group = "org.jetbrains.koog.graph.checker"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}