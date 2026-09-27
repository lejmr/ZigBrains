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
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.psi.PsiManager

/** [ZigConfigProducerTest] driven the way the gutter icon and the editor/project-view context menus drive it. */
class ZigConfigProducerTestTest : ZigPlatformTestCase() {
    private val producer get() = RunConfigurationProducer.getInstance(ZigConfigProducerTest::class.java)

    private fun testConfig(context: ConfigurationContext): ZigExecConfigTest? =
        producer.createConfigurationFromContext(context)?.configuration as ZigExecConfigTest?

    fun testNamedTestGetsAFilterWithTheDecodedName() {
        val file = configure("x.zig", "te<caret>st \"caf\\u{e9} \\\"quoted\\\"\" {}\n")
        val config = testConfig(contextAtCaret())!!
        assertEquals(nioPath(file), config.filePath.path)
        assertEquals("café \"quoted\" (x.zig)", config.name)
        // The quoted name must come back verbatim through the same splitter the run uses
        assertEquals(listOf("--test-filter", "café \"quoted\""), config.compilerArgs.argsSplit())
    }

    fun testPlainNamedTestIsQuoted() {
        configure("x.zig", "te<caret>st \"adds\" {}\n")
        val config = testConfig(contextAtCaret())!!
        assertEquals("--test-filter \"adds\"", config.compilerArgs.args)
        assertEquals(listOf("--test-filter", "adds"), config.compilerArgs.argsSplit())
    }

    fun testDeclTestFiltersByIdentifier() {
        configure("x.zig", "fn foo() void {}\nte<caret>st foo {}\n")
        val config = testConfig(contextAtCaret())!!
        assertEquals("foo (x.zig)", config.name)
        assertEquals(listOf("--test-filter", "foo"), config.compilerArgs.argsSplit())
    }

    fun testAnonymousTestRunsTheWholeFileWithoutAFilter() {
        val file = configure("x.zig", "te<caret>st {}\n")
        val config = testConfig(contextAtCaret())!!
        assertEquals(nioPath(file), config.filePath.path)
        assertEquals("all tests in x.zig", config.name)
        assertEquals("", config.compilerArgs.args)
    }

    fun testCaretOutsideAnyTestRunsTheWholeFile() {
        val file = configure("x.zig", "const st<caret>d = @import(\"std\");\ntest \"a\" {}\n")
        val config = testConfig(contextAtCaret())!!
        assertEquals(nioPath(file), config.filePath.path)
        assertEquals("all tests in x.zig", config.name)
        assertEquals("", config.compilerArgs.args)
    }

    fun testFileWithoutTestsOffersNothingOutsideATest() {
        configure("x.zig", "const st<caret>d = @import(\"std\");\n")
        assertNull(testConfig(contextAtCaret()))
    }

    fun testMainYieldsNoTestConfiguration() {
        configure("x.zig", "pub fn ma<caret>in() void {}\ntest \"a\" {}\n")
        assertNull(testConfig(contextAtCaret()))
    }

    fun testDirectoryWithAZigFileRunsTheDirectory() {
        val file = myFixture.addFileToProject("pkg/x.zig", "test \"a\" {}\n")
        val directory = PsiManager.getInstance(project).findDirectory(file.virtualFile.parent)!!
        val config = testConfig(contextOf(directory))!!
        assertEquals(directory.virtualFile.toNioPath(), config.filePath.path)
        assertEquals("all tests in pkg", config.name)
    }

    fun testDirectoryWithoutZigFilesOffersNothing() {
        val file = myFixture.addFileToProject("docs/readme.txt", "nothing to run here\n")
        val directory = PsiManager.getInstance(project).findDirectory(file.virtualFile.parent)!!
        assertNull(testConfig(contextOf(directory)))
    }

    fun testExistingConfigurationIsFoundAgainFromTheSameContext() {
        configure("x.zig", "te<caret>st \"adds\" {}\ntest \"other\" {}\n")
        val context = contextAtCaret()
        val fromContext = producer.createConfigurationFromContext(context)!!
        RunManager.getInstance(project).addConfiguration(fromContext.configurationSettings)

        assertSame(fromContext.configurationSettings, producer.findExistingConfiguration(context))
        assertTrue(producer.isConfigurationFromContext(fromContext.configuration as ZigExecConfigTest, context))

        // The same file's other test is a different configuration
        val other = myFixture.file.findElementAt(myFixture.file.text.lastIndexOf("test"))!!
        assertFalse(producer.isConfigurationFromContext(fromContext.configuration as ZigExecConfigTest, contextOf(other)))
    }
}
