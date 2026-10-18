plugins {
    id("zb-module")
    kotlin("plugin.serialization")
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.idea)
        plugin("com.redhat.devtools.lsp4ij", libs.versions.lsp4ij.get())
    }
    compileOnly(libs.kotlin.serialization) {
        isTransitive = false
    }
    compileOnly(libs.lsp4j)
    implementation(project(":shared")) {
        isTransitive = false
    }
}

tasks {
    compileJava {
        enabled = false
    }
}
