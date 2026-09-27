plugins {
    id("zb-module")
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.idea)
        bundledModules("intellij.platform.coverage", "intellij.platform.coverage.agent")
    }
    implementation(project(":shared")) {
        isTransitive = false
    }
    testImplementation(libs.junit)
}

tasks {
    compileJava {
        enabled = false
    }
}
