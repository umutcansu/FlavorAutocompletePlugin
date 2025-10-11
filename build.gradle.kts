// build.gradle.kts

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0" // Consider using a version consistent with your IDE target
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "io.github.umutcansu.flavorautocomplete"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        androidStudio("2024.1.2.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        bundledPlugin("org.intellij.groovy")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("org.jetbrains.plugins.gradle") // Fixed the typo with a leading space
    }
}

// Configuration for the plugin. This will be patched into plugin.xml at build time.
intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
            // untilBuild is automatically set to "241.*"
        }

        // Richer change notes for the Marketplace.
        changeNotes = """
            <ul>
                <li>Initial version of the plugin.</li>
                <li>Provides autocompletion for all product flavors across all modules.</li>
                <li>Supports both Kotlin and Groovy files.</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}