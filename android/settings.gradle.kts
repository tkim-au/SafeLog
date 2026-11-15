pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Playground SDKs (Vehicle, NaviHelper, TextToSpeech ...). Only these groups are looked up here.
        // TextToSpeech 3.3.1 depends on ai.pleos.playground.security:universal-access, a sub-group of its own.
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content { includeGroupByRegex("ai\\.pleos\\.playground(\\..*)?") }
        }
    }
}
rootProject.name = "SafeLog"
include(":app")
// The judging engine, ledger and server client in plain Kotlin/JVM: unit-tested against the Python engine
// (parity cases) and the FastAPI server (golden ledger cases), no Android needed.
include(":core")
