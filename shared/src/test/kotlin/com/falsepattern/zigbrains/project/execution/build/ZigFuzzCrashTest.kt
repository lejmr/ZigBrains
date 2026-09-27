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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Lines are verbatim zig 0.16.0 output from `zig build test --fuzz -Doptimize=ReleaseSafe` (forever and =20M modes).
class ZigFuzzCrashTest {
    private val crashLine = "error: test 'main.test.fuzz' terminated with signal ABRT; input saved to '.zig-cache/f/crash'"

    @Test
    fun parsesTheSavedInputLine() {
        assertEquals(
            ZigFuzzCrash("main.test.fuzz", "terminated with signal ABRT", ".zig-cache/f/crash"),
            parseFuzzCrash(crashLine)
        )
    }

    @Test
    fun ignoresTheOtherFailureLines() {
        // crash before the fuzzer sent its coverage id: zig prints no input path, there is nothing to open
        assertNull(parseFuzzCrash("error: test process unexpectedly terminated with signal ABRT"))
        assertNull(parseFuzzCrash("failed command: ./.zig-cache/o/bd3d190bcaef80fe6cb229c4ef227a61/test --cache-dir=./.zig-cache --seed=0xc60eda0b --listen=-"))
        assertNull(parseFuzzCrash("thread 11325513 panic: found it"))
        assertNull(parseFuzzCrash("+- run test failure"))
        // non-fuzz `zig build test` replaying the corpus reports crashes differently, no saved input either
        assertNull(parseFuzzCrash("error: 'main.test.fuzz' terminated with signal ABRT with stderr:"))
    }

    @Test
    fun detectorReassemblesPtyChunksAndStripsColors() {
        val found = ArrayList<ZigFuzzCrash>()
        val detector = ZigFuzzCrashDetector(found::add)
        // zig colors "error:" red and prints the tree dim; a PTY delivers CRLF in arbitrary chunks
        detector.feed("\u001B[2mtest\r\n+- run test failure\u001B[0m\r\nthread 11325513 panic: found it\r\n\u001B[31merror:\u001B[0m test 'main.test.fuzz' termin")
        assertTrue(found.isEmpty())
        detector.feed("ated with signal ABRT; input saved to '.zig-cache/f/crash'\r\nfailed command: ./.zig-cache/o/x/test --listen=-\r\n")
        assertEquals(listOf(ZigFuzzCrash("main.test.fuzz", "terminated with signal ABRT", ".zig-cache/f/crash")), found)
    }

    @Test
    fun onlyFuzzBuildsAreWatched() {
        assertTrue(isFuzzBuild(listOf("test", "--fuzz", "--webui", "-Doptimize=ReleaseSafe")))
        assertTrue(isFuzzBuild(listOf("test", "--fuzz=100000")))
        assertFalse(isFuzzBuild(listOf("test", "--webui")))
        assertFalse(isFuzzBuild(listOf("test", "--summary", "all")))
    }

    @Test
    fun replayCommandDropsFuzzFlagsAndNamesTheCorpusStep() {
        val crash = ZigFuzzCrash("main.test.fuzz", "terminated with signal ABRT", ".zig-cache/f/crash")
        val cmd = replayCommand(crash, listOf("test", "--fuzz", "--webui", "-Doptimize=ReleaseSafe"))
        assertTrue(cmd, cmd.startsWith("cp '.zig-cache/f/crash' src/fuzz-crash && zig build test -Doptimize=ReleaseSafe"))
        assertTrue(cmd, cmd.contains("@embedFile(\"fuzz-crash\")"))
        assertFalse(cmd, cmd.contains("--fuzz") || cmd.contains("--webui"))
    }
}
