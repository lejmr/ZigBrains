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
 *
 * A test's outcome is never taken from its own `N/M` line: it is only tentative until the next `N/M`
 * line, a summary line, a file/exit marker or [finish] resolves it, since zig can still print a leak or
 * `std.log.err` report (or even a genuine `FAIL`) after an `OK`/`SKIP` suffix that looked final.
 *
 * A test name containing a literal newline is not handled: it would split into two lines and break parsing.
 */
class ZigTestOutputParser(private val listener: Listener) {
    interface Listener {
        fun fileStarted(path: String)
        fun fileFinished(path: String)
        fun testStarted(displayName: String, fullName: String)
        fun testPassed()
        fun testSkipped()
        fun testFailed(message: String, details: String)
        fun output(text: String)
    }

    private enum class Verdict { OPEN, PASS, SKIP }

    private class PendingTest {
        var verdict = Verdict.OPEN
        var message: String? = null
        var errorName: String? = null
        // Once true, a further FAIL/log line has already committed this test to failure, so a later bare
        // "OK"/"SKIP" (zig's own runner line, not the test's) must not flip it back.
        var locked = false
        val details = StringBuilder()

        fun appendDetail(line: String) {
            if (details.isNotEmpty()) details.append('\n')
            details.append(line)
        }
    }

    private var currentFile: String? = null
    private var pending: PendingTest? = null
    private var testStartedInFile = false
    private val fileOutputBuffer = StringBuilder()

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

        if (SUMMARY_LINE.matches(text) || ALL_PASSED_LINE.matches(text)) {
            resolvePending()
            emitOutput(text)
            return
        }

        val p = pending
        if (p != null && handlePendingLine(p, text)) return

        if (p != null) {
            if (p.verdict == Verdict.OPEN) p.appendDetail(text) else emitOutput(text)
            return
        }

        emitOutput(text)
    }

    fun finish() {
        resolvePending(emptyMessageFallback = "no result reported")
        currentFile?.let {
            currentFile = null
            listener.fileFinished(it)
        }
    }

    /** Returns true if [text] was consumed as a verdict-affecting line rather than plain content. */
    private fun handlePendingLine(p: PendingTest, text: String): Boolean {
        if (p.locked) {
            p.appendDetail(text)
            return true
        }
        when (text) {
            "OK" -> { flushGatheringAsOutput(p); p.verdict = Verdict.PASS; return true }
            "SKIP" -> { flushGatheringAsOutput(p); p.verdict = Verdict.SKIP; return true }
        }
        val failMatch = FAIL_LINE.matchEntire(text)
        if (failMatch != null) {
            applyFail(p, failMatch)
            return true
        }
        if (LOG_ERR_LINE.containsMatchIn(text)) {
            applyLoggedFailure(p, text)
            return true
        }
        return false
    }

    private fun handleMarker(text: String) {
        when {
            text.startsWith(FILE_MARKER) -> {
                val path = text.removePrefix(FILE_MARKER)
                resolvePending(emptyMessageFallback = "no result reported")
                currentFile?.let { listener.fileFinished(it) }
                currentFile = path
                testStartedInFile = false
                fileOutputBuffer.setLength(0)
                listener.fileStarted(path)
            }

            text.startsWith(EXIT_MARKER) -> {
                val exitCode = text.removePrefix(EXIT_MARKER).trim().toIntOrNull() ?: 0
                resolvePending(emptyMessageFallback = "no result reported")
                if (exitCode != 0 && !testStartedInFile) {
                    listener.testStarted("build failed", "build failed")
                    listener.testFailed("zig test exited with $exitCode", fileOutputBuffer.toString())
                }
            }

            else -> listener.output(text)
        }
    }

    private fun handleTestLine(match: MatchResult) {
        val fullName = match.groupValues[3]
        val suffix = match.groupValues[4]
        val displayName = displayNameOf(fullName)
        testStartedInFile = true
        listener.testStarted(displayName, fullName)

        val p = PendingTest()
        when {
            suffix.endsWith("OK") -> {
                val extra = suffix.removeSuffix("OK")
                if (extra.isNotEmpty()) emitOutput(extra)
                p.verdict = Verdict.PASS
            }

            suffix.endsWith("SKIP") -> {
                val extra = suffix.removeSuffix("SKIP")
                if (extra.isNotEmpty()) emitOutput(extra)
                p.verdict = Verdict.SKIP
            }

            else -> {
                val failMatch = FAIL_LINE.matchEntire(suffix)
                when {
                    failMatch != null -> applyFail(p, failMatch)
                    LOG_ERR_LINE.containsMatchIn(suffix) -> applyLoggedFailure(p, suffix)
                    else -> p.message = suffix.ifEmpty { null }
                }
            }
        }
        pending = p
    }

    /** [match] is a whole line's `(.*)FAIL \((.+)\)` split: the prefix is the test's own output, never part
     *  of the failure message, so it does not combine with whatever was already gathered in [p.message]. */
    private fun applyFail(p: PendingTest, match: MatchResult) {
        val (prefix, errorName) = match.destructured
        if (prefix.isNotEmpty()) emitOutput(prefix)
        p.errorName = errorName
        p.verdict = Verdict.OPEN
        p.locked = true
    }

    /** A test that was still gathering (never locked into a failure) turned out to pass/skip after all, so
     *  whatever was gathered so far was the test's own output, not a failure message. */
    private fun flushGatheringAsOutput(p: PendingTest) {
        if (p.verdict != Verdict.OPEN) return
        p.message?.let { emitOutput(it) }
        if (p.details.isNotEmpty()) {
            for (line in p.details.toString().split("\n")) emitOutput(line)
        }
        p.message = null
        p.details.setLength(0)
    }

    private fun applyLoggedFailure(p: PendingTest, line: String) {
        if (p.message == null) p.message = "logged errors or leaked memory"
        p.verdict = Verdict.OPEN
        p.locked = true
        p.appendDetail(line)
    }

    private fun resolvePending(emptyMessageFallback: String = "") {
        val p = pending ?: return
        pending = null
        when (p.verdict) {
            Verdict.PASS -> listener.testPassed()
            Verdict.SKIP -> listener.testSkipped()
            Verdict.OPEN -> {
                val errorName = p.errorName
                val text = p.message
                val message = when {
                    errorName != null && !text.isNullOrEmpty() -> "$errorName: $text"
                    errorName != null -> errorName
                    !text.isNullOrEmpty() -> text
                    else -> emptyMessageFallback
                }
                listener.testFailed(message, p.details.toString())
            }
        }
    }

    private fun emitOutput(text: String) {
        if (currentFile != null && !testStartedInFile) {
            if (fileOutputBuffer.isNotEmpty()) fileOutputBuffer.append('\n')
            fileOutputBuffer.append(text)
        }
        listener.output(text)
    }

    companion object {
        // Tried first: an unambiguous "...OK"/"...SKIP"/"...FAIL (X)" ending, matched greedily so a test
        // name that itself contains "..." doesn't get split early.
        private val GREEDY_TEST_LINE = Regex("""^(\d+)/(\d+) (.+)\.\.\.(OK|SKIP|FAIL \(.+\))$""")

        // Fallback: split at the first "...", since the remainder (the failure text) may itself contain dots.
        private val TEST_LINE = Regex("""^(\d+)/(\d+) (.+?)\.\.\.(.*)$""")

        // Group 1 is leading output the test printed without a newline before the real outcome; group 2 is
        // the error name, which may itself contain spaces (e.g. `error.@"foo bar"` prints `FAIL (foo bar)`).
        private val FAIL_LINE = Regex("""^(.*)FAIL \((.+)\)$""")
        private val LOG_ERR_LINE = Regex("""^\[[^\]]+\] \(err\): """)
        private val SUMMARY_LINE = Regex("""^\d+ passed; \d+ skipped; \d+ failed\.$""")
        private val ALL_PASSED_LINE = Regex("""^All \d+ tests passed\.$""")
        private const val TEST_SEPARATOR = ".test."

        const val FILE_MARKER = "zigbrains:test-file "
        const val EXIT_MARKER = "zigbrains:test-exit "

        fun displayNameOf(fullName: String): String {
            val idx = fullName.indexOf(TEST_SEPARATOR)
            return if (idx >= 0) fullName.substring(idx + TEST_SEPARATOR.length) else fullName.substringAfterLast('.')
        }
    }
}
