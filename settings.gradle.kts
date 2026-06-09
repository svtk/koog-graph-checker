pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "koog-graph-checker"

include("compiler-plugin")
include("gradle-plugin")

include("ide-plugin")