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
 * Thin adapter between the raw text a `zig test` process writes and the SMTRunner test tree: hands
 * complete lines (the platform splitter already delivers one newline-terminated line per call) to a pure
 * [ZigTestOutputParser], and translates the parser's events into
 * [com.intellij.execution.testframework.sm.runner.GeneralTestEventsProcessor] calls.
 *
 * One suite per file (opened/closed via [FILE_MARKER] lines emitted by the process handler running each
 * file's `zig test` invocation), one test at a time within it -- zig test output is strictly sequential.
 *
 * [processConsistentText] runs on the process's stdout/stderr reader threads and
 * [flushBufferOnProcessTermination] on the process-termination thread, so both are synchronized.
 */
class ZigTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties) :
    OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties), ZigTestOutputParser.Listener {

    private val parser = ZigTestOutputParser(this)

    private var currentFile: String? = null
    private var currentTestName: String? = null
    private var lastOutputType: Key<*> = ProcessOutputTypes.STDOUT

    @Synchronized
    override fun processConsistentText(text: String, outputType: Key<*>) {
        // Markers (the only thing ever sent on SYSTEM) never reach output(), so there's no need to guard
        // against SYSTEM here; keeping it unconditional means non-marker SYSTEM text (if any ever arrives)
        // still keeps its own key instead of silently becoming stdout.
        lastOutputType = outputType
        parser.line(text.removeSuffix("\n"), outputType == ProcessOutputTypes.SYSTEM)
    }

    @Synchronized
    override fun flushBufferOnProcessTermination(exitCode: Int) {
        super.flushBufferOnProcessTermination(exitCode)
        // Resolves any still-pending test and closes the open suite (if any); nothing further to do here.
        parser.finish()
    }

    override fun fileStarted(path: String) {
        currentFile = path
        processor?.onSuiteStarted(TestSuiteStartedEvent(suiteName(path), "file://$path"))
    }

    override fun fileFinished(path: String) {
        currentFile = null
        processor?.onSuiteFinished(TestSuiteFinishedEvent(suiteName(path)))
    }

    override fun testStarted(displayName: String, fullName: String) {
        currentTestName = displayName
        val locationUrl = currentFile?.let { ZigTestLocator.url(it, fullName) }
        processor?.onTestStarted(TestStartedEvent(displayName, locationUrl))
    }

    override fun testPassed(durationMs: Long) {
        val name = currentTestName ?: return
        currentTestName = null
        // zig test doesn't report real per-test timing; start-to-resolution wall clock is ~0 for the
        // common single-line case and otherwise dominated by how long the test took to print, so it's not
        // meaningful either way.
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

    override fun testFailed(message: String, details: String, durationMs: Long) {
        val name = currentTestName ?: return
        currentTestName = null
        processor?.let {
            it.onTestFailure(TestFailedEvent(name, message, details, false, null, null))
            it.onTestFinished(TestFinishedEvent(name, durationMs))
        }
    }

    override fun output(text: String) {
        processor?.onUncapturedOutput(text + "\n", lastOutputType)
    }

    private fun suiteName(path: String): String = Path.of(path).fileName?.toString() ?: path

    companion object {
        const val FILE_MARKER = "zigbrains:test-file "
    }
}
