plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
}

// Independent builds share sources, but must not share mutable build outputs.
project(":protocol") {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("protocol"))
}
