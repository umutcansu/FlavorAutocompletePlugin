// build.gradle.kts

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "io.github.umutcansu.flavorautocomplete"
version = "1.0.3"

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
        bundledPlugin("org.jetbrains.plugins.gradle")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
        }

        changeNotes = """
            <ul>
                <li>Fixed binary compatibility with Android Studio Narwhal and newer.</li>
                <li>Removed upper version bound for broader IDE compatibility.</li>
            </ul>
        """.trimIndent()
    }
    publishing {
        token.set(providers.gradleProperty("ORG_JETBRAINS_INTELLIJ_PLATFORM_PUBLISH_TOKEN"))
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