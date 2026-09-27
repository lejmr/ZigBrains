import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.settings
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask

plugins {
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("org.jetbrains.gradle.plugin.idea-ext")
    `maven-publish`
    id("zb-common")
    id("nl.littlerobots.version-catalog-update")
}
val publishVersions = listOf("253", "261", "262")

val pluginVersion = property("pluginVersion").toString()
val pluginSinceBuild = property("pluginSinceBuild").toString()
val pluginUntilBuild = property("pluginUntilBuild").toString()
val runIdeTarget = property("runIdeTarget").toString()
val lspCompat = property("lspCompat").toString().toBoolean()
val cidrCompat = property("cidrCompat").toString().toBoolean()

val pluginVersionFull get() = "$pluginVersion-$pluginSinceBuild"

group = "com.falsepattern"
version = pluginVersionFull

idea.project.settings.runConfigurations {
    create("1. Run with LSP", org.jetbrains.gradle.ext.Gradle::class.java) {
        taskNames = listOf("runIde")
        scriptParameters = "-PlspCompat=true"
    }
    create("2. Run without LSP", org.jetbrains.gradle.ext.Gradle::class.java) {
        taskNames = listOf("runIde")
        scriptParameters = "-PlspCompat=false"
    }
    create("3. Run with LSP, no cidr", org.jetbrains.gradle.ext.Gradle::class.java) {
        taskNames = listOf("runIde")
        scriptParameters = "-PlspCompat=true -PcidrCompat=false -PrunIdeTarget=idea"
    }
    create("4. Run without LSP, no cidr", org.jetbrains.gradle.ext.Gradle::class.java) {
        taskNames = listOf("runIde")
        scriptParameters = "-PlspCompat=false -PcidrCompat=false -PrunIdeTarget=idea"
    }
}

tasks {
    processResources {
        from("LICENSE")
        from("licenses") {
            into("licenses")
        }
    }
}

dependencies {
    intellijPlatform {
        when(runIdeTarget) {
            "idea" -> intellijIdea(libs.versions.intellij.idea)
            "clion" -> clion(libs.versions.intellij.clion)
        }

        pluginVerifier()
        zipSigner()
        if (lspCompat) {
            plugin("com.redhat.devtools.lsp4ij", libs.versions.lsp4ij.get())
        }
        pluginModule(runtimeOnly(project(":shared")))
        pluginModule(runtimeOnly(project(":webui")))
        pluginModule(runtimeOnly(project(":coverage")))
        if (cidrCompat) {
            pluginModule(runtimeOnly(project(":cidr")))
            pluginModule(runtimeOnly(project(":clion")))
        }
        if (lspCompat) {
            pluginModule(runtimeOnly(project(":lsp")))
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        version = pluginVersionFull

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog

        changeNotes = provider { pluginVersion }.map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML
                )
            }
        }

        ideaVersion {
            sinceBuild = pluginSinceBuild
            if (pluginUntilBuild.isNotBlank()) {
                untilBuild = pluginUntilBuild
            } else {
                untilBuild = provider { null }
            }
        }
    }

    signing {
        certificateChainFile = file("secrets/chain.crt")
        privateKeyFile = file("secrets/private.pem")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    pluginVerification {
        ides {
            select {
                types = listOf(
                    IntelliJPlatformType.IntellijIdea,
                    IntelliJPlatformType.CLion
                )
            }
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = "https://codeberg.org/FalsePattern/ZigBrains"
}

tasks {
    publishPlugin {
        dependsOn(patchChangelog)
    }
    compileJava {
        enabled = false
    }
    classes {
        enabled = false
    }
    verifyPluginSignature {
        certificateChainFile = file("secrets/chain.crt")
        inputArchiveFile = signPlugin.map { it.signedArchiveFile }.get()
        dependsOn(signPlugin)
    }
    publishPlugin {
        enabled = false
    }
    buildSearchableOptions {
        jvmArgs("--sun-misc-unsafe-memory-access=allow")
    }
    patchChangelog {
        enabled = false
    }
}


fun distFile(it: String) = layout.buildDirectory.file("dist/com.falsepattern.zigbrains-$pluginVersion-$it-signed.zip")

publishVersions.forEach {
    tasks.register<PublishPluginTask>("jbpublish-$it").configure {
        archiveFile = distFile(it)
        token = providers.environmentVariable("IJ_PUBLISH_TOKEN")
        channels = if (pluginVersion.contains("-")) listOf("nightly") else listOf("default")
        setDependsOn(dependsOn.filter { if (it is TaskProvider<*>) it.name != "signPlugin" && it.name != "buildPlugin" else true })
    }
    tasks.named("publish").configure {
        dependsOn("jbpublish-$it")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.falsepattern"
            artifactId = "zigbrains"
            version = pluginVersion

            publishVersions.forEach {
                artifact(distFile(it)) {
                    classifier = "$it-signed"
                    extension = "zip"
                }
            }
        }
    }
    repositories {
        maven {
            name = "mavenpattern"
            url = uri("https://mvn.falsepattern.com/releases/");
            credentials {
                username = System.getenv("MAVEN_DEPLOY_USER")
                password = System.getenv("MAVEN_DEPLOY_PASSWORD")
            }
        }
    }
}