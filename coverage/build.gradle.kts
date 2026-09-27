import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("zb-module")
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.idea)
        bundledModules("intellij.platform.coverage", "intellij.platform.coverage.agent")
        testFramework(TestFrameworkType.Platform)
    }
    implementation(project(":shared")) {
        isTransitive = false
    }
    testImplementation(project(":shared"))
    testImplementation(libs.junit)
}

tasks {
    compileJava {
        enabled = false
    }
}
