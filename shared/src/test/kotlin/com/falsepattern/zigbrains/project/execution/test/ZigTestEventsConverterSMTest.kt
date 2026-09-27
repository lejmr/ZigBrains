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

package com.falsepattern.zigbrains.project.execution.test

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

/**
 * [ZigTestEventsConverter] feeding the platform's SMTRunner event processor, checked through the resulting
 * [SMTestProxy] tree: a two-file run inside one package, the second file also running an imported module's test.
 */
class ZigTestEventsConverterSMTest : ZigPlatformTestCase() {
    fun testTwoFileRunBuildsPackageFileModuleAndTestNodes() {
        val root = myFixture.tempDirPath
        val a = "$root/pkg/a.zig"
        val b = "$root/pkg/b.zig"
        myFixture.addFileToProject("pkg/build.zig", "")
        myFixture.addFileToProject("pkg/a.zig", "test \"one\" {}\ntest \"two\" {}\n")
        myFixture.addFileToProject("pkg/b.zig", "test \"three\" {}\n")
        myFixture.addFileToProject("pkg/helper.zig", "test \"x\" {}\n")

        val tree = feed(
            "zigbrains:test-package $root/pkg" to ProcessOutputTypes.SYSTEM,
            "zigbrains:test-file $a" to ProcessOutputTypes.SYSTEM,
            "1/2 a.test.one...OK" to ProcessOutputTypes.STDERR,
            "2/2 a.test.two...expected 1, found 2" to ProcessOutputTypes.STDERR,
            "FAIL (TestExpectedEqual)" to ProcessOutputTypes.STDERR,
            "1 passed; 0 skipped; 1 failed." to ProcessOutputTypes.STDERR,
            "zigbrains:test-exit 1" to ProcessOutputTypes.SYSTEM,
            "zigbrains:test-file $b" to ProcessOutputTypes.SYSTEM,
            "1/2 b.test.three...OK" to ProcessOutputTypes.STDERR,
            "2/2 helper.test.x...SKIP" to ProcessOutputTypes.STDERR,
            "1 passed; 1 skipped; 0 failed." to ProcessOutputTypes.STDERR,
            "zigbrains:test-exit 0" to ProcessOutputTypes.SYSTEM,
        )

        val pkg = tree.children.single()
        assertEquals("pkg", pkg.name)
        assertEquals(VfsUtilCore.pathToUrl("$root/pkg/build.zig"), pkg.locationUrl)
        assertEquals(listOf("a.zig", "b.zig"), pkg.children.map { it.name })

        val (fileA, fileB) = pkg.children
        assertEquals(VfsUtilCore.pathToUrl(a), fileA.locationUrl)
        assertEquals(listOf("one", "two"), fileA.children.map { it.name })
        assertEquals(ZigTestLocator.url(a, "a.test.one"), fileA.children[0].locationUrl)
        assertTrue(fileA.children[0].isPassed)
        assertTrue(fileA.children[1].isDefect)
        assertTrue(fileA.isDefect)

        // b.zig's own test sits directly under the file; the imported module's test under a "helper" sub-suite
        assertEquals(listOf("three", "helper"), fileB.children.map { it.name })
        val helper = fileB.children[1]
        assertTrue(helper.isSuite)
        assertEquals(VfsUtilCore.pathToUrl("$root/pkg/helper.zig"), helper.locationUrl)
        val x = helper.children.single()
        assertEquals("x", x.name)
        assertEquals(ZigTestLocator.url(b, "helper.test.x"), x.locationUrl)
        assertTrue(x.isIgnored)
        assertTrue(fileB.children[0].isPassed)
    }

    private fun feed(vararg lines: Pair<String, Key<*>>): SMTestProxy.SMRootTestProxy {
        val config = RunManager.getInstance(project).createConfiguration("zig tests", firstConfigFactory<ZigConfigTypeTest>()).configuration as ZigExecConfigTest
        val properties = ZigTestConsoleProperties(config, DefaultRunExecutor.getRunExecutorInstance())
        Disposer.register(testRootDisposable, properties)
        val root = SMTestProxy.SMRootTestProxy()
        val processor = GeneralToSMTRunnerEventsConvertor(project, root, ZigTestConsoleProperties.FRAMEWORK_NAME)
        Disposer.register(testRootDisposable, processor)
        val converter = properties.createTestEventsConverter(ZigTestConsoleProperties.FRAMEWORK_NAME, properties)
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
