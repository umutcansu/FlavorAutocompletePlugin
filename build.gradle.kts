// build.gradle.kts

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "io.github.umutcansu.flavorautocomplete"
version = "1.0.7"

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
                <li>Fixed flavor detection on Android Studio Narwhal 3 (Platform 253) by adding missing org.jetbrains.android dependency.</li>
                <li>Added multi-API reflection fallback chain (GradleAndroidModel + AndroidModuleModel) for broad AS version support.</li>
                <li>Added PSI-based build file parsing as fallback when reflection fails (supports both Groovy and Kotlin DSL).</li>
                <li>Added support for flavors defined in applied script files (apply from: 'xxx.gradle').</li>
                <li>Improved cache invalidation: cache now tracks build.gradle/build.gradle.kts file changes.</li>
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