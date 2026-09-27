/*
 * This file is part of ZigBrains.
 *
 * Copyright (C) 2023-2026 FalsePattern
 * All Rights Reserved
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * ZigBrains is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, only version 3 of the License.
 *
 * ZigBrains is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ZigBrains. If not, see <https://www.gnu.org/licenses/>.
 */

package com.falsepattern.zigbrains.project.execution.build

import com.falsepattern.zigbrains.ZigPlatformTestCase
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import java.nio.file.Path

/** [ZigConfigProducerBuild]: `main` in a project with a `build.zig` runs through `zig build run`. */
class ZigConfigProducerBuildTest : ZigPlatformTestCase() {
    private val producer get() = RunConfigurationProducer.getInstance(ZigConfigProducerBuild::class.java)

    private fun buildConfig(context: ConfigurationContext): ZigExecConfigBuild? =
        producer.createConfigurationFromContext(context)?.configuration as ZigExecConfigBuild?

    private val buildZig = """
        const std = @import("std");

        pub fn build(b: *std.Build) void {
            const exe = b.addExecutable(.{
                .name = "demo",
                .root_module = b.createModule(.{ .root_source_file = b.path("src/main.zig") }),
            });
            b.installArtifact(exe);
        }
    """.trimIndent()

    fun testMainNextToABuildZigBuildsAndRuns() {
        val root = Path.of(myFixture.tempDirPath)
        myFixture.addFileToProject("build.zig", buildZig)
        configure("src/main.zig", "pub fn ma<caret>in() void {}\n")
        val context = contextAtCaret()

        val config = buildConfig(context)!!
        assertEquals("Build and Run", config.name)
        assertEquals("run", config.buildSteps.args)
        assertEquals("install", config.debugBuildSteps.args)
        assertEquals(root, config.workingDirectory.path)
        assertEquals(root.resolve("zig-out").resolve("bin").resolve("demo"), config.exePath.path)
        assertTrue(producer.isConfigurationFromContext(config, context))
    }

    fun testBuildZigWithoutAnExecutableLeavesTheExePathEmpty() {
        myFixture.addFileToProject("build.zig", "pub fn build(b: *@import(\"std\").Build) void { _ = b; }\n")
        configure("main.zig", "pub fn ma<caret>in() void {}\n")
        val config = buildConfig(contextAtCaret())!!
        assertEquals("install", config.debugBuildSteps.args)
        assertNull(config.exePath.path)
    }

    fun testBuildZigItselfBuildsAndRunsWithoutADebugStep() {
        configure("build.zig", buildZig.replace("pub fn build", "pub fn bu<caret>ild"))
        val context = contextAtCaret()
        val config = buildConfig(context)!!
        assertEquals("Build and Run", config.name)
        assertEquals("run", config.buildSteps.args)
        assertEquals("", config.debugBuildSteps.args)
        assertTrue(producer.isConfigurationFromContext(config, context))
    }

    fun testMainWithoutABuildZigOffersNothing() {
        configure("main.zig", "pub fn ma<caret>in() void {}\n")
        assertNull(buildConfig(contextAtCaret()))
    }

    fun testOtherFunctionsOfferNothing() {
        myFixture.addFileToProject("build.zig", buildZig)
        configure("src/main.zig", "fn hel<caret>per() void {}\npub fn main() void {}\n")
        assertNull(buildConfig(contextAtCaret()))
    }
}
