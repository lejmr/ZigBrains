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

import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.events.TestFailedEvent
import com.intellij.execution.testframework.sm.runner.events.TestFinishedEvent
import com.intellij.execution.testframework.sm.runner.events.TestIgnoredEvent
import com.intellij.execution.testframework.sm.runner.events.TestStartedEvent
import com.intellij.execution.testframework.sm.runner.events.TestSuiteFinishedEvent
import com.intellij.execution.testframework.sm.runner.events.TestSuiteStartedEvent
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.toNioPathOrNull
import java.io.File
import java.nio.file.Path

/**
 * Adapts the raw text a `zig test` process writes to the SMTRunner test tree: hands complete lines to a
 * pure [ZigTestOutputParser] and translates the parser's events into
 * [com.intellij.execution.testframework.sm.runner.GeneralTestEventsProcessor] calls.
 *
 * One suite per file, opened/closed via [ZigTestOutputParser.FILE_MARKER] lines from the process handler;
 * when a run spans more than one package, an extra suite per package is opened/closed via
 * [ZigTestOutputParser.PACKAGE_MARKER] lines. A test whose module path (`ZigTestOutputParser.modulePathOf`)
 * differs from the running file's own stem belongs to a file it imports (e.g. `root.zig` running
 * `Lexer.zig`'s tests): such tests are nested under an extra sub-suite named after that module path,
 * located at the resolved sibling file when one exists.
 */
class ZigTestEventsConverter(testFrameworkName: String, private val consoleProperties: TestConsoleProperties) :
    OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties), ZigTestOutputParser.Listener {

    private val parser = ZigTestOutputParser(this)

    private var currentFile: String? = null
    private var currentTestName: String? = null
    private var currentModulePath: String? = null

    @Synchronized
    override fun processConsistentText(text: String, outputType: Key<*>) {
        parser.line(text.removeSuffix("\n"), outputType == ProcessOutputTypes.SYSTEM)
    }

    @Synchronized
    override fun flushBufferOnProcessTermination(exitCode: Int) {
        super.flushBufferOnProcessTermination(exitCode)
        parser.finish()
    }

    override fun packageStarted(path: String) {
        processor?.onSuiteStarted(TestSuiteStartedEvent(packageSuiteName(path), VfsUtilCore.pathToUrl(Path.of(path, "build.zig").toString())))
    }

    override fun packageFinished(path: String) {
        processor?.onSuiteFinished(TestSuiteFinishedEvent(packageSuiteName(path)))
    }

    override fun fileStarted(path: String) {
        currentFile = path
        currentModulePath = null
        processor?.onSuiteStarted(TestSuiteStartedEvent(suiteName(path), VfsUtilCore.pathToUrl(path)))
    }

    override fun fileFinished(path: String) {
        closeModuleSuite()
        currentFile = null
        processor?.onSuiteFinished(TestSuiteFinishedEvent(suiteName(path)))
    }

    override fun testStarted(displayName: String, fullName: String) {
        currentTestName = displayName
        val file = currentFile
        val modulePath = ZigTestOutputParser.modulePathOf(fullName)
        if (file == null || modulePath == stemOf(file)) {
            closeModuleSuite()
        } else if (modulePath != currentModulePath) {
            closeModuleSuite()
            currentModulePath = modulePath
            val moduleFile = moduleFileOf(file, modulePath)
            val locationUrl = if (File(moduleFile).isFile) VfsUtilCore.pathToUrl(moduleFile) else null
            processor?.onSuiteStarted(TestSuiteStartedEvent(modulePath, locationUrl))
        }
        val locationUrl = currentFile?.let { ZigTestLocator.url(it, fullName) }
        processor?.onTestStarted(TestStartedEvent(displayName, locationUrl))
    }

    private fun closeModuleSuite() {
        val modulePath = currentModulePath ?: return
        currentModulePath = null
        processor?.onSuiteFinished(TestSuiteFinishedEvent(modulePath))
    }

    override fun testPassed() {
        val name = currentTestName ?: return
        currentTestName = null
        processor?.onTestFinished(TestFinishedEvent(name, null))
    }

    override fun testSkipped() {
        val name = currentTestName ?: return
        currentTestName = null
        processor?.let {
            it.onTestIgnored(TestIgnoredEvent(name, "", null))
            it.onTestFinished(TestFinishedEvent(name, null))
        }
    }

    override fun testFailed(message: String, details: String) {
        val name = currentTestName ?: return
        currentTestName = null
        processor?.let {
            it.onTestFailure(TestFailedEvent(name, message, details, false, null, null))
            it.onTestFinished(TestFinishedEvent(name, null))
        }
    }

    override fun output(text: String) {
        processor?.onUncapturedOutput(text + "\n", ProcessOutputTypes.STDOUT)
    }

    private fun suiteName(path: String): String = Path.of(path).fileName?.toString() ?: path

    private fun packageSuiteName(path: String): String {
        val dir = Path.of(path)
        val base = consoleProperties.project.guessProjectDir()?.toNioPathOrNull()
        val rel = base?.let { runCatching { it.relativize(dir) }.getOrNull() }?.toString()
        return when {
            rel.isNullOrEmpty() || rel == "." -> dir.fileName?.toString() ?: consoleProperties.project.name
            else -> rel
        }
    }

    companion object {
        /** The running file's own module name, as zig would print it: its filename without the `.zig`
         *  extension (a dotted stem such as `a.b.zig` compares as the whole string `a.b`, not just `b`). */
        fun stemOf(runningFile: String): String = Path.of(runningFile).fileName?.toString()?.removeSuffix(".zig") ?: runningFile

        /** The sibling file a module path resolves to, mirroring [ZigTestLocator]'s own file resolution:
         *  dots become path separators, relative to the running file's directory. Pure path arithmetic --
         *  the caller checks whether the result actually exists. */
        fun moduleFileOf(runningFile: String, modulePath: String): String =
            Path.of(runningFile).resolveSibling(modulePath.replace('.', '/') + ".zig").toString()
    }
}
