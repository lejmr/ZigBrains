plugins {
    id("zb-module")
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.idea)
        bundledModule("intellij.platform.ui.jcef")
    }
    implementation(project(":shared")) {
        isTransitive = false
    }
}

tasks {
    compileJava {
        enabled = false
    }
}
