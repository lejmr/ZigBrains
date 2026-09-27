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
import com.falsepattern.zigbrains.project.execution.firstConfigFactory
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.runner.GeneralToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Path

/**
 * [ZigFuzzEventsConverter] feeding the platform's SMTRunner event processor: real `zig build --fuzz --webui`
 * output (progress spinner, embedded web UI log line, one crash), checked through the resulting
 * [SMTestProxy] tree the same way [com.falsepattern.zigbrains.project.execution.test.ZigTestEventsConverterSMTest]
 * checks the ordinary test tree - this is the user-visible contract (a crash becomes a failed, navigable
 * tree node under "Fuzzing"), not just the pure [ZigFuzzCrashDetector] parsing it depends on.
 */
class ZigFuzzEventsConverterSMTest : ZigPlatformTestCase() {
    fun testCrashBecomesAFailedNode() {
        val root = myFixture.tempDirPath
        myFixture.addFileToProject("build.zig", "")

        val tree = feed(
            Path.of(root),
            "Build Summary: 1/3 steps succeeded" to ProcessOutputTypes.STDOUT,
            "web interface listening at http://127.0.0.1:1337/" to ProcessOutputTypes.STDOUT,
            "error: test 'main.test.fuzz' terminated with signal 6; input saved to '.zig-cache/f/crash'" to ProcessOutputTypes.STDOUT,
        )

        val suite = tree.children.single()
        assertEquals(ZigFuzzEventsConverter.SUITE_NAME, suite.name)
        assertTrue(suite.isDefect)
        val crash = suite.children.single()
        assertEquals("main.test.fuzz", crash.name)
        assertTrue(crash.isDefect)
        assertEquals(VfsUtilCore.pathToUrl(Path.of(root, ".zig-cache/f/crash").toString()), crash.locationUrl)
        assertEquals("terminated with signal 6", crash.errorMessage)
    }

    fun testTwoCrashesOfTheSameTestGetDistinctNodes() {
        val root = myFixture.tempDirPath
        myFixture.addFileToProject("build.zig", "")

        val tree = feed(
            Path.of(root),
            "error: test 'main.test.fuzz' terminated with signal 6; input saved to '.zig-cache/f/crash'" to ProcessOutputTypes.STDOUT,
            "error: test 'main.test.fuzz' exited with code 1; input saved to '.zig-cache/f/crash'" to ProcessOutputTypes.STDOUT,
        )

        val suite = tree.children.single()
        assertEquals(listOf("main.test.fuzz", "main.test.fuzz (2)"), suite.children.map { it.name })
    }

    private fun feed(workDir: Path, vararg lines: Pair<String, Key<*>>): SMTestProxy.SMRootTestProxy {
        val config = RunManager.getInstance(project).createConfiguration("fuzz", firstConfigFactory<ZigConfigTypeBuild>()).configuration as ZigExecConfigBuild
        config.workingDirectory.path = workDir
        val properties = ZigFuzzConsoleProperties(config, DefaultRunExecutor.getRunExecutorInstance())
        Disposer.register(testRootDisposable, properties)
        val root = SMTestProxy.SMRootTestProxy()
        val processor = GeneralToSMTRunnerEventsConvertor(project, root, "Zig Test")
        Disposer.register(testRootDisposable, processor)
        val converter = properties.createTestEventsConverter("Zig Test", properties)
        converter.setProcessor(processor)

        processor.onStartTesting()
        for ((line, type) in lines) {
            converter.process(line + "\n", type)
        }
        converter.flushBufferOnProcessTermination(0)
        processor.onFinishTesting()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return root
    }
}
