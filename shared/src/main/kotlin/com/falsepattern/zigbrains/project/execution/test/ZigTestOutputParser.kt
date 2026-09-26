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

/**
 * Pure Kotlin parser for the line-oriented output that `zig test` writes when stdout/stderr is a pipe
 * (no PTY), e.g. `1/4 fail.test.passes...OK`. Has no dependency on IntelliJ APIs so it can be unit-tested
 * directly; [ZigTestEventsConverter] adapts its events to the SMTRunner test tree.
 */
class ZigTestOutputParser(private val listener: Listener, private val clock: () -> Long = System::currentTimeMillis) {
    interface Listener {
        fun fileStarted(path: String)
        fun fileFinished(path: String)
        fun testStarted(displayName: String, fullName: String)
        fun testPassed(durationMs: Long)
        fun testSkipped()
        fun testFailed(message: String, details: String, durationMs: Long)
        fun output(text: String)
    }

    private class PendingFailure(val startTime: Long, var message: String?) {
        var errorName: String? = null
        val details = StringBuilder()

        fun appendDetail(line: String) {
            if (details.isNotEmpty()) details.append('\n')
            details.append(line)
        }
    }

    private var currentFile: String? = null
    private var pending: PendingFailure? = null

    /** One line of output, without its trailing newline. [system] is true for the SYSTEM marker channel. */
    fun line(text: String, system: Boolean) {
        if (system) {
            handleMarker(text)
            return
        }

        val testMatch = TEST_LINE.matchEntire(text)
        if (testMatch != null) {
            resolvePending()
            handleTestLine(testMatch)
            return
        }

        val failMatch = FAIL_LINE.matchEntire(text)
        if (failMatch != null && pending != null) {
            pending!!.errorName = failMatch.groupValues[1]
            return
        }

        if (SUMMARY_LINE.matches(text) || ALL_PASSED_LINE.matches(text)) {
            resolvePending()
            listener.output(text)
            return
        }

        val p = pending
        if (p != null) {
            p.appendDetail(text)
            return
        }

        listener.output(text)
    }

    fun finish() {
        resolvePending()
        currentFile?.let {
            currentFile = null
            listener.fileFinished(it)
        }
    }

    private fun handleMarker(text: String) {
        if (!text.startsWith(FILE_MARKER_PREFIX)) {
            listener.output(text)
            return
        }
        val path = text.removePrefix(FILE_MARKER_PREFIX)

        pending?.let {
            pending = null
            listener.testFailed("no result reported", it.details.toString(), clock() - it.startTime)
        }
        currentFile?.let { listener.fileFinished(it) }
        currentFile = path
        listener.fileStarted(path)
    }

    private fun handleTestLine(match: MatchResult) {
        val fullName = match.groupValues[3]
        val suffix = match.groupValues[4]
        val displayName = displayNameOf(fullName)
        val start = clock()
        listener.testStarted(displayName, fullName)
        when (suffix) {
            "OK" -> listener.testPassed(clock() - start)
            "SKIP" -> listener.testSkipped()
            else -> pending = PendingFailure(start, suffix.ifEmpty { null })
        }
    }

    private fun resolvePending() {
        val p = pending ?: return
        pending = null
        val message = when {
            p.errorName != null && !p.message.isNullOrEmpty() -> "${p.errorName}: ${p.message}"
            p.errorName != null -> p.errorName!!
            else -> p.message.orEmpty()
        }
        listener.testFailed(message, p.details.toString(), clock() - p.startTime)
    }

    companion object {
        private val TEST_LINE = Regex("""^(\d+)/(\d+) (.+?)\.\.\.(.*)$""")
        private val FAIL_LINE = Regex("""^FAIL \((\w+)\)$""")
        private val SUMMARY_LINE = Regex("""^\d+ passed; \d+ skipped; \d+ failed\.$""")
        private val ALL_PASSED_LINE = Regex("""^All \d+ tests passed\.$""")
        private const val TEST_SEPARATOR = ".test."

        // Must match ZigTestEventsConverter.FILE_MARKER exactly. Duplicated (rather than referenced) to
        // keep this class free of any dependency on the IntelliJ-facing converter, so it stays plain-JUnit
        // testable.
        private const val FILE_MARKER_PREFIX = "zigbrains:test-file "

        fun displayNameOf(fullName: String): String {
            val idx = fullName.indexOf(TEST_SEPARATOR)
            return if (idx >= 0) fullName.substring(idx + TEST_SEPARATOR.length) else fullName.substringAfterLast('.')
        }
    }
}
