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

package com.falsepattern.zigbrains.profiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixture: trimmed `perf script` of a `perf record -g` run (perf 6.8, aarch64) of a ReleaseSafe zig program, with hand-added edge cases at the end. */
class PerfScriptTest {
    private val profile = javaClass.getResourceAsStream("/perf-script.txt")!!.use { PerfScript.parse(it.reader().readText()) }

    private fun fn(name: String) = profile.functions.single { it.name == name }

    @Test
    fun aHeaderWithFramesIsASampleAndAHeaderWithoutIsNot() {
        assertEquals(9, profile.samples)
        // the last sample has a single frame
        assertEquals(8, profile.stacked)
    }

    @Test
    fun selfIsTheFirstFrameAndTotalEveryFrame() {
        assertEquals(2, fn("hot.collatz").self)
        assertEquals(2, fn("hot.collatz").total)
        assertEquals(0, fn("hot.sieve").self)
        assertEquals(1, fn("hot.sieve").total)
        assertEquals(1, fn("compiler_rt.memset").self)
        assertEquals(3, fn("hot.main").self)
        assertEquals(9, fn("hot.main").total)
        assertEquals(7, fn("start.posixCallMainAndExit").total)
    }

    @Test
    fun kernelFramesCountLikeAnyOther() {
        assertEquals(1, fn("__pi_caches_clean_inval_pou").self)
        assertEquals(1, fn("el0t_64_sync").total)
    }

    @Test
    fun unknownSymbolsMergePerBinaryAndTheZeroTerminatorIsNoFrame() {
        assertEquals(1, fn("[unknown] (libc.so.6)").self)
        assertEquals(2, fn("[unknown] (libc.so.6)").total)
        assertNull(profile.functions.find { it.name == "0x0" || it.name == "[unknown]" || it.name.startsWith("0x") })
    }

    @Test
    fun parenthesesInsideSymbolsStayWithTheSymbol() {
        assertEquals(1, fn("(anonymous namespace)::Pool::grab(unsigned long)").self)
    }

    @Test
    fun perfHasNoSourceLocations() {
        assertNull(fn("hot.main").file)
    }

    @Test
    fun sortsBySelfThenTotal() {
        assertEquals(listOf("hot.main", "hot.collatz"), profile.functions.take(2).map { it.name })
    }

    @Test
    fun inlinedFramesFromADwarfRecordingAreFunctions() {
        val dwarf = PerfScript.parse("hot 1 1.0: 1 task-clock:ppp: \n\t 1022748 hot.sieve+0xa4 (inlined)\n\t 1022748 hot.main+0xa4 (/tmp/prof/hot)\n\n")
        assertEquals(1, dwarf.samples)
        assertEquals(1, dwarf.stacked)
        assertEquals(1, dwarf.functions.single { it.name == "hot.sieve" }.self)
    }

    @Test
    fun garbageParsesToNothing() {
        assertEquals(0, PerfScript.parse("addr2line_configure /tmp/prof/hot: addr2line configuration failed\n\t\u0000").samples)
        assertEquals(0, PerfScript.parse("").samples)
    }
}
