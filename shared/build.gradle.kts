import org.jetbrains.intellij.platform.gradle.tasks.GenerateLexerTask
import org.jetbrains.intellij.platform.gradle.tasks.GenerateParserTask

plugins {
    id("zb-module")
    kotlin("plugin.serialization")
    id("org.jetbrains.intellij.platform.grammarkit")
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.idea)
		bundledModule("intellij.spellchecker")
		bundledModule("intellij.platform.testRunner")
		bundledModule("intellij.platform.smRunner")
    }
    compileOnly(libs.kotlin.serialization) {
        isTransitive = false
    }
    testImplementation(libs.junit)
}

sourceSets {
    main {
        java {
            srcDir(tasks.generateParser.map { it.targetRootOutputDir })
            srcDir(tasks.generateLexer.map { it.targetRootOutputDir })
        }
    }
}

val rootPackagePath = "com/falsepattern/zigbrains"
val grammarSources = layout.projectDirectory.dir("src/main/grammar")

tasks {
    // region grammarkit
    generateLexer {
        description = "Generate Zig Lexer"
        purgeOldFiles = true
        sourceFile = grammarSources.file("Zig.flex")
        pathToClass = "$rootPackagePath/zig/lexer/ZigFlexLexer.java"
    }

    register<GenerateLexerTask>("generateLexerString") {
        description = "Generate Zig String Lexer"
        purgeOldFiles = true
        sourceFile = grammarSources.file("ZigString.flex")
        pathToClass = "$rootPackagePath/zig/lexerstring/ZigLexerString.java"
    }

    generateParser {
        description = "Generate Zig Parser"
        purgeOldFiles = true
        sourceFile = grammarSources.file("Zig.bnf")
        pathToParser = "$rootPackagePath/zig/parser/ZigParser.java"
        pathToPsiRoot = "$rootPackagePath/zig/psi"
    }

    register<GenerateLexerTask>("generateZonLexer") {
        description = "Generate ZON Lexer"
        purgeOldFiles = true
        sourceFile = grammarSources.file("Zon.flex")
        pathToClass = "$rootPackagePath/zon/lexer/ZonFlexLexer.java"
    }

    register<GenerateParserTask>("generateZonParser") {
        description = "Generate ZON Parser"
        purgeOldFiles = true
        sourceFile = grammarSources.file("Zon.bnf")
        pathToParser = "$rootPackagePath/zon/parser/ZonParser.java"
        pathToPsiRoot = "$rootPackagePath/zon/psi"
    }

    register<DefaultTask>("generateGrammars") {
        description = "Generate all grammar lexers/parsers"
        group = "grammarkit"
        dependsOn("generateLexer", "generateLexerString", "generateParser")
        dependsOn("generateZonLexer", "generateZonParser")
    }

    compileJava {
        dependsOn("generateGrammars")
    }

    compileKotlin {
        dependsOn("generateGrammars")
    }
}
