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
    // Transitive here (unlike the non-transitive main dependency above): tests need shared's own classes (ZigFile,
    // ZigFileType) plus its plugin module descriptor on the classpath for the test-only plugin.xml to resolve.
    testImplementation(project(":shared"))
    testImplementation(libs.junit)
}

tasks {
    compileJava {
        enabled = false
    }
}
