@file:Suppress("UnstableApiUsage")

import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("zb-settings")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    setUrl("https://mvn.falsepattern.com/releases")
                    name = "mavenpattern"
                }
            }
            filter {
                includeModule("com.redhat.devtools.intellij", "lsp4ij")
            }
        }
        intellijPlatform {
            defaultRepositories()
            snapshots()
        }
    }
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
}

rootProject.name = "com.falsepattern.zigbrains"

includeBuild("build-logic")
include(":shared")
include(":webui")
include(":lsp")
include(":cidr")
include(":clion")
