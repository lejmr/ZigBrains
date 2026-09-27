plugins {
    id("zb-module")
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.idea)
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
