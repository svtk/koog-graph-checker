plugins {
    alias(libs.plugins.kotlin.jvm)
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
    }
}

// Target the JVM version the IDE plugin runs on (IntelliJ 2026.1 → JDK 21), so this shared module is
// consumable by both the IDE plugin (JVM 21) and the compiler plugin (newer JVM).
kotlin {
    jvmToolchain(21)
}
