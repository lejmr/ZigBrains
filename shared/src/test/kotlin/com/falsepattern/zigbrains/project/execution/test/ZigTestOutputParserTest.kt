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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures below were captured verbatim from real `zig 0.16.0` runs (`zig test <file> 2>&1 | cat`, i.e.
 * pipe mode, no PTY) against small throwaway files. See the task description for the source of each file.
 */
class ZigTestOutputParserTest {
    private class RecordingListener : ZigTestOutputParser.Listener {
        val events = mutableListOf<String>()
        val passedDurations = mutableListOf<Long>()
        val failures = mutableListOf<Triple<String, String, Long>>()

        override fun fileStarted(path: String) {
            events += "FILE_STARTED:$path"
        }

        override fun fileFinished(path: String) {
            events += "FILE_FINISHED:$path"
        }

        override fun testStarted(displayName: String, fullName: String) {
            events += "TEST_STARTED:$displayName:$fullName"
        }

        override fun testPassed(durationMs: Long) {
            passedDurations += durationMs
            events += "TEST_PASSED"
        }

        override fun testSkipped() {
            events += "TEST_SKIPPED"
        }

        override fun testFailed(message: String, details: String, durationMs: Long) {
            failures += Triple(message, details, durationMs)
            events += "TEST_FAILED:$message"
        }

        override fun output(text: String) {
            events += "OUTPUT:$text"
        }
    }

    private fun feed(parser: ZigTestOutputParser, fixture: String) {
        for (line in fixture.split("\n")) {
            parser.line(line, system = false)
        }
    }

    private fun marker(path: String) = "zigbrains:test-file $path"

    private val allPassingFixture = """
1/2 pass.test.one...OK
2/2 pass.test.two...OK
All 2 tests passed.
""".trim('\n')

    private val mixedFixture = """
1/4 fail.test.passes...OK
2/4 fail.test.fails...expected 1, found 2
FAIL (TestExpectedEqual)
/Users/milos/.local/zig-0.16.0-patched/lib/zig/std/testing.zig:118:17: 0x102e7ffb7 in expectEqualInner__anon_36372 (test)
                return error.TestExpectedEqual;
                ^
/Users/milos/.local/zig-0.16.0-patched/lib/zig/std/testing.zig:83:5: 0x102e80047 in expectEqual (test)
    return expectEqualInner(T, expected, actual);
    ^
/private/tmp/x/fail.zig:3:16: 0x102e80087 in test.fails (test)
test "fails" { try std.testing.expectEqual(@as(u8, 1), 2); }
               ^
3/4 fail.test.skips...SKIP
4/4 fail.test_0...OK
2 passed; 1 skipped; 1 failed.
error: the following test command failed with exit code 1:
/Users/milos/.cache/zig/o/8751e6ef3d5b3a938b9f5e2ebea0a40d/test --seed=0xb61fa63
""".trim('\n')

    private val panicFixture = """
1/3 panic.test.before...OK
2/3 panic.test.boom...thread 9765154 panic: boom
/private/tmp/x/panic.zig:3:15: 0x102967f27 in test.boom (test)
test "boom" { @panic("boom"); }
              ^
/Users/milos/.local/zig-0.16.0-patched/lib/zig/compiler/test_runner.zig:291:25: 0x102945ad3 in mainTerminal (test)
        if (test_fn.func()) |_| {
                        ^
???:?:?: 0x18d047e7f in start (/usr/lib/dyld)
error: the following test command terminated with signal ABRT:
/Users/milos/.cache/zig/o/f5e4c2bbd24b46494ec6cbd1e651a5e7/test --seed=0x1e086a38
""".trim('\n')

    private val leakFixture = """
1/2 leak.test.leaks...OK
[DebugAllocator] (err): memory address 0x102520000 leaked: 
/private/tmp/x/leak.zig:3:47: 0x1023dbf33 in test.leaks (test)
    const p = try std.testing.allocator.create(u8);
                                              ^
???:?:?: 0x18d047e7f in start (/usr/lib/dyld)

2/2 leak.test.after leak...OK
All 2 tests passed.
1 errors were logged.
1 tests leaked memory.
error: the following test command failed with exit code 1:
/Users/milos/.cache/zig/o/78ae02b1ab8d170b1acd85ac40e051be/test --seed=0x1faaf5a3
""".trim('\n')

    @Test
    fun allTestsPass() {
        val listener = RecordingListener()
        val parser = ZigTestOutputParser(listener)
        feed(parser, allPassingFixture)
        parser.finish()

        assertEquals(
            listOf(
                "TEST_STARTED:one:pass.test.one",
                "TEST_PASSED",
                "TEST_STARTED:two:pass.test.two",
                "TEST_PASSED",
                "OUTPUT:All 2 tests passed.",
            ),
            listener.events,
        )
    }

    @Test
    fun passSkipAndFailureWithTraceAndAnonymousTest() {
        val listener = RecordingListener()
        val parser = ZigTestOutputParser(listener)
        feed(parser, mixedFixture)
        parser.finish()

        assertEquals(
            listOf(
                "TEST_STARTED:passes:fail.test.passes",
                "TEST_PASSED",
                "TEST_STARTED:fails:fail.test.fails",
                "TEST_FAILED:TestExpectedEqual: expected 1, found 2",
                "TEST_STARTED:skips:fail.test.skips",
                "TEST_SKIPPED",
                "TEST_STARTED:test_0:fail.test_0",
                "TEST_PASSED",
                "OUTPUT:2 passed; 1 skipped; 1 failed.",
                "OUTPUT:error: the following test command failed with exit code 1:",
                "OUTPUT:/Users/milos/.cache/zig/o/8751e6ef3d5b3a938b9f5e2ebea0a40d/test --seed=0xb61fa63",
            ),
            listener.events,
        )

        assertEquals(1, listener.failures.size)
        val (message, details, _) = listener.failures[0]
        assertEquals("TestExpectedEqual: expected 1, found 2", message)
        // The FAIL(...) line itself is consumed, not part of the trace; the 9 stack-trace lines remain.
        assertEquals(9, details.lines().size)
        assertTrue(details.contains("expectEqualInner__anon_36372"))
        assertTrue(details.contains("test \"fails\""))
    }

    @Test
    fun panicAbortsWithoutFailOrSummaryLine() {
        val listener = RecordingListener()
        val parser = ZigTestOutputParser(listener)
        feed(parser, panicFixture)
        // the process aborted: no summary line ever arrives, only process termination
        parser.finish()

        assertEquals(1, listener.failures.size)
        val (message, details, _) = listener.failures[0]
        // no "FAIL (...)" line was ever printed for a panic, so the message is just the suffix of the
        // test's own start line
        assertEquals("thread 9765154 panic: boom", message)
        assertTrue(details.contains("test \"boom\""))
        assertTrue(details.contains("error: the following test command terminated with signal ABRT:"))

        assertEquals(
            listOf(
                "TEST_STARTED:before:panic.test.before",
                "TEST_PASSED",
                "TEST_STARTED:boom:panic.test.boom",
                "TEST_FAILED:thread 9765154 panic: boom",
            ),
            listener.events,
        )
    }

    @Test
    fun memoryLeakDoesNotFailTheTestItself() {
        val listener = RecordingListener()
        val parser = ZigTestOutputParser(listener)
        feed(parser, leakFixture)
        parser.finish()

        // the leak is only ever logged as loose output after the fact; the leaking test itself still
        // reports OK, and the following test still runs and passes
        assertEquals(0, listener.failures.size)
        assertEquals(
            listOf(
                "TEST_STARTED:leaks:leak.test.leaks",
                "TEST_PASSED",
                "OUTPUT:[DebugAllocator] (err): memory address 0x102520000 leaked: ",
                "OUTPUT:/private/tmp/x/leak.zig:3:47: 0x1023dbf33 in test.leaks (test)",
                "OUTPUT:    const p = try std.testing.allocator.create(u8);",
                "OUTPUT:                                              ^",
                "OUTPUT:???:?:?: 0x18d047e7f in start (/usr/lib/dyld)",
                "OUTPUT:",
                "TEST_STARTED:after leak:leak.test.after leak",
                "TEST_PASSED",
                "OUTPUT:All 2 tests passed.",
                "OUTPUT:1 errors were logged.",
                "OUTPUT:1 tests leaked memory.",
                "OUTPUT:error: the following test command failed with exit code 1:",
                "OUTPUT:/Users/milos/.cache/zig/o/78ae02b1ab8d170b1acd85ac40e051be/test --seed=0x1faaf5a3",
            ),
            listener.events,
        )
    }

    @Test
    fun emptyRunPrintsOnlyTheAllPassedSummary() {
        val listener = RecordingListener()
        val parser = ZigTestOutputParser(listener)
        parser.line("All 0 tests passed.", system = false)
        parser.finish()

        assertEquals(listOf("OUTPUT:All 0 tests passed."), listener.events)
        assertEquals(0, listener.failures.size)
    }

    @Test
    fun twoFilesAreSeparatedByFileMarkers() {
        val listener = RecordingListener()
        val parser = ZigTestOutputParser(listener)

        parser.line(marker("/tmp/a.zig"), system = true)
        parser.line("1/1 a.test.one...OK", system = false)
        parser.line("All 1 tests passed.", system = false)
        parser.line(marker("/tmp/b.zig"), system = true)
        parser.line("1/1 b.test.two...OK", system = false)
        parser.finish()

        assertEquals(
            listOf(
                "FILE_STARTED:/tmp/a.zig",
                "TEST_STARTED:one:a.test.one",
                "TEST_PASSED",
                "OUTPUT:All 1 tests passed.",
                "FILE_FINISHED:/tmp/a.zig",
                "FILE_STARTED:/tmp/b.zig",
                "TEST_STARTED:two:b.test.two",
                "TEST_PASSED",
                "FILE_FINISHED:/tmp/b.zig",
            ),
            listener.events,
        )
    }

    @Test
    fun processEndingMidTestResolvesUsingWhateverWasAccumulated() {
        val listener = RecordingListener()
        val parser = ZigTestOutputParser(listener)

        parser.line(marker("/tmp/fail.zig"), system = true)
        parser.line("1/4 fail.test.passes...OK", system = false)
        parser.line("2/4 fail.test.fails...expected 1, found 2", system = false)
        parser.line("FAIL (TestExpectedEqual)", system = false)
        parser.line("/some/trace/line:1:1: in expectEqual (test)", system = false)
        // process is killed here, no summary line, no more markers
        parser.finish()

        assertEquals(1, listener.failures.size)
        val (message, details, _) = listener.failures[0]
        assertEquals("TestExpectedEqual: expected 1, found 2", message)
        assertEquals("/some/trace/line:1:1: in expectEqual (test)", details)

        assertEquals(
            listOf(
                "FILE_STARTED:/tmp/fail.zig",
                "TEST_STARTED:passes:fail.test.passes",
                "TEST_PASSED",
                "TEST_STARTED:fails:fail.test.fails",
                "TEST_FAILED:TestExpectedEqual: expected 1, found 2",
                "FILE_FINISHED:/tmp/fail.zig",
            ),
            listener.events,
        )
    }

    @Test
    fun markerWhileATestIsStillFailingClosesItAsNoResultReported() {
        val listener = RecordingListener()
        val parser = ZigTestOutputParser(listener)

        parser.line(marker("/tmp/one.zig"), system = true)
        parser.line("1/1 one.test.stuck...", system = false)
        parser.line("some trace line", system = false)
        parser.line(marker("/tmp/two.zig"), system = true)

        assertEquals(1, listener.failures.size)
        val (message, details, _) = listener.failures[0]
        assertEquals("no result reported", message)
        assertEquals("some trace line", details)
        assertNull(listener.failures.getOrNull(1))
    }

    @Test
    fun durationIsMeasuredFromStartLineToResolution() {
        val listener = RecordingListener()
        var now = 1_000L
        val parser = ZigTestOutputParser(listener, clock = { now })

        now = 1_000L
        parser.line("1/2 d.test.one...OK", system = false)
        assertEquals(0L, listener.passedDurations[0])

        now = 2_000L
        parser.line("2/2 d.test.two...", system = false)
        now = 2_750L
        parser.line("FAIL (Boom)", system = false)
        now = 3_000L
        parser.finish()

        assertEquals(1, listener.failures.size)
        assertEquals(1_000L, listener.failures[0].third)
    }
}
