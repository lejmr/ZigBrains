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
import com.intellij.openapi.util.Key
import java.nio.file.Path

/**
 * Thin adapter between the raw text a `zig test` process writes and the SMTRunner test tree: buffers
 * incoming output into complete lines, hands them to a pure [ZigTestOutputParser], and translates the
 * parser's events into [com.intellij.execution.testframework.sm.runner.GeneralTestEventsProcessor] calls.
 *
 * One suite per file (opened/closed via [FILE_MARKER] lines emitted by the process handler running each
 * file's `zig test` invocation), one test at a time within it -- zig test output is strictly sequential.
 */
class ZigTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties) :
    OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties), ZigTestOutputParser.Listener {

    private val parser = ZigTestOutputParser(this)
    private val pendingLines = mutableMapOf<Key<*>, StringBuilder>()

    private var currentFile: String? = null
    private var currentTestName: String? = null

    override fun processConsistentText(text: String, outputType: Key<*>) {
        val buffer = pendingLines.getOrPut(outputType) { StringBuilder() }
        buffer.append(text)
        var newlineIndex = buffer.indexOf("\n")
        while (newlineIndex >= 0) {
            val line = buffer.substring(0, newlineIndex)
            buffer.delete(0, newlineIndex + 1)
            parser.line(line, outputType == ProcessOutputTypes.SYSTEM)
            newlineIndex = buffer.indexOf("\n")
        }
    }

    override fun flushBufferOnProcessTermination(exitCode: Int) {
        // Flushes the base class's own internal splitter, delivering any last partial chunk through
        // processConsistentText before we look at our own line buffers below.
        super.flushBufferOnProcessTermination(exitCode)

        for ((outputType, buffer) in pendingLines) {
            if (buffer.isNotEmpty()) {
                parser.line(buffer.toString(), outputType == ProcessOutputTypes.SYSTEM)
                buffer.setLength(0)
            }
        }
        parser.finish()

        // Safety net: parser.finish() above resolves any test/file it knows about. If, for whatever
        // reason, one is still open at this point (e.g. testStarted fired but no resolution ever came
        // through this listener), close it here so the tree doesn't hang open forever.
        currentTestName?.let { name ->
            currentTestName = null
            getProcessor()?.let {
                it.onTestFailure(TestFailedEvent(name, null, "test process exited before reporting a result", null, false, null, null, null, null, false, false, -1L))
                it.onTestFinished(TestFinishedEvent(name, null))
            }
        }
        currentFile?.let { path ->
            currentFile = null
            getProcessor()?.onSuiteFinished(TestSuiteFinishedEvent(suiteName(path)))
        }
    }

    override fun fileStarted(path: String) {
        currentFile = path
        getProcessor()?.onSuiteStarted(TestSuiteStartedEvent(suiteName(path), null))
    }

    override fun fileFinished(path: String) {
        currentFile = null
        getProcessor()?.onSuiteFinished(TestSuiteFinishedEvent(suiteName(path)))
    }

    override fun testStarted(displayName: String, fullName: String) {
        currentTestName = displayName
        val locationUrl = currentFile?.let { ZigTestLocator.url(it, fullName) }
        getProcessor()?.onTestStarted(TestStartedEvent(displayName, locationUrl))
    }

    override fun testPassed(durationMs: Long) {
        val name = currentTestName ?: return
        currentTestName = null
        getProcessor()?.onTestFinished(TestFinishedEvent(name, durationMs))
    }

    override fun testSkipped() {
        val name = currentTestName ?: return
        currentTestName = null
        getProcessor()?.let {
            it.onTestIgnored(TestIgnoredEvent(name, "", null))
            it.onTestFinished(TestFinishedEvent(name, null))
        }
    }

    override fun testFailed(message: String, details: String, durationMs: Long) {
        val name = currentTestName ?: return
        currentTestName = null
        getProcessor()?.let {
            it.onTestFailure(TestFailedEvent(name, null, message, details, false, null, null, null, null, false, false, durationMs))
            it.onTestFinished(TestFinishedEvent(name, durationMs))
        }
    }

    override fun output(text: String) {
        getProcessor()?.onUncapturedOutput(text + "\n", ProcessOutputTypes.STDOUT)
    }

    private fun suiteName(path: String): String = Path.of(path).fileName?.toString() ?: path

    companion object {
        const val FILE_MARKER = "zigbrains:test-file "
    }
}
