pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal { content { includeGroup("com.hcebox") } }
        google()
        mavenCentral()
    }
}
rootProject.name = "Android-Native-Driver"
include(":app", ":protocol")
project(":protocol").projectDir = file("../../reader/protocol")
