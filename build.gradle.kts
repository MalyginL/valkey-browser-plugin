
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    // Valkey/Redis client
    implementation("redis.clients:jedis:5.2.0")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
    }
}

// Disable code instrumentation (requires JBR) - read more: https://plugins.jetbrains.com/docs/intellij/intellij-platform-gradle-plugin-code-instrumentation.html
tasks.named("instrumentCode") {
    enabled = false
}
tasks.named("instrumentTestCode") {
    enabled = false
}
