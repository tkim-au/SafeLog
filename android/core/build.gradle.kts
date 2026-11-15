import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // Korean strings in assertion messages
    systemProperty("file.encoding", "UTF-8")
    // ServerSyncTest runs only when a server is up: SAFELOG_TEST_SERVER=http://127.0.0.1:8099 ./gradlew :core:test
    System.getenv("SAFELOG_TEST_SERVER")?.let { environment("SAFELOG_TEST_SERVER", it) }
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
