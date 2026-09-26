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

    private class PendingTest(val startTime: Long, var message: String?) {
        var errorName: String? = null
        val details = StringBuilder()

        fun appendDetail(line: String) {
            if (details.isNotEmpty()) details.append('\n')
            details.append(line)
        }

        /** Sends whatever was accumulated so far to [listener] as ordinary output, e.g. because the test
         *  actually passed or was skipped despite having looked like it was failing. */
        fun flushAsOutput(listener: Listener) {
            message?.let { listener.output(it) }
            if (details.isNotEmpty()) {
                for (line in details.toString().split("\n")) listener.output(line)
            }
        }
    }

    private var currentFile: String? = null
    private var pending: PendingTest? = null

    /** One line of output, without its trailing newline. [system] is true for the SYSTEM marker channel. */
    fun line(text: String, system: Boolean) {
        if (system) {
            handleMarker(text)
            return
        }

        val testMatch = GREEDY_TEST_LINE.matchEntire(text) ?: TEST_LINE.matchEntire(text)
        if (testMatch != null) {
            resolvePending()
            handleTestLine(testMatch)
            return
        }

        val p = pending
        if (p != null) {
            when (text) {
                "OK" -> {
                    pending = null
                    val duration = clock() - p.startTime
                    p.flushAsOutput(listener)
                    listener.testPassed(duration)
                    return
                }

                "SKIP" -> {
                    pending = null
                    p.flushAsOutput(listener)
                    listener.testSkipped()
                    return
                }
            }

            val failMatch = FAIL_LINE.matchEntire(text)
            if (failMatch != null) {
                p.errorName = failMatch.groupValues[1]
                return
            }
        }

        if (SUMMARY_LINE.matches(text) || ALL_PASSED_LINE.matches(text)) {
            resolvePending()
            listener.output(text)
            return
        }

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

        resolvePending(emptyMessageFallback = "no result reported")
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
        when {
            // Covers both a bare "OK" suffix and a test that wrote output without a trailing newline,
            // e.g. "...partialOK": whatever precedes "OK" is the test's own output, not a failure.
            suffix.endsWith("OK") -> {
                val extra = suffix.removeSuffix("OK")
                if (extra.isNotEmpty()) listener.output(extra)
                listener.testPassed(clock() - start)
            }

            suffix == "SKIP" -> listener.testSkipped()

            else -> {
                // A test can fail without ever printing a separate "FAIL (...)" line, e.g.
                // "...FAIL (Boom)" as the suffix itself; recognize that here so the message ends up
                // "Boom", not the literal "FAIL (Boom)".
                val failInSuffix = FAIL_LINE.matchEntire(suffix)
                pending = if (failInSuffix != null) {
                    PendingTest(start, null).apply { errorName = failInSuffix.groupValues[1] }
                } else {
                    PendingTest(start, suffix.ifEmpty { null })
                }
            }
        }
    }

    private fun resolvePending(emptyMessageFallback: String = "") {
        val p = pending ?: return
        pending = null
        val errorName = p.errorName
        val text = p.message
        val message = when {
            errorName != null && !text.isNullOrEmpty() -> "$errorName: $text"
            errorName != null -> errorName
            !text.isNullOrEmpty() -> text
            else -> emptyMessageFallback
        }
        listener.testFailed(message, p.details.toString(), clock() - p.startTime)
    }

    companion object {
        // Tried first: an unambiguous "...OK"/"...SKIP" ending, matched greedily so a test name that
        // itself contains "..." (e.g. "wait... what", or literally "ok...OK") doesn't get split early.
        private val GREEDY_TEST_LINE = Regex("""^(\d+)/(\d+) (.+)\.\.\.(OK|SKIP)$""")

        // Fallback for everything else (failures, panics, ...): split at the first "...", since the
        // remainder (the failure text) may itself contain dots or "...".
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
