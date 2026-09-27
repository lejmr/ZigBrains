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
        assertEquals(7, profile.samples)
    }

    @Test
    fun selfIsTheFirstFrameAndTotalEveryFrame() {
        assertEquals(2, fn("hot.collatz").self)
        assertEquals(2, fn("hot.collatz").total)
        assertEquals(0, fn("hot.sieve").self)
        assertEquals(1, fn("hot.sieve").total)
        assertEquals(1, fn("compiler_rt.memset").self)
        assertEquals(2, fn("hot.main").self)
        assertEquals(7, fn("hot.main").total)
        assertEquals(7, fn("start.posixCallMainAndExit").total)
    }

    @Test
    fun kernelFramesCountLikeAnyOther() {
        assertEquals(1, fn("__pi_caches_clean_inval_pou").self)
        assertEquals(1, fn("el0t_64_sync").total)
    }

    @Test
    fun unknownSymbolsFallBackToTheAddressAndTheZeroTerminatorIsNoFrame() {
        assertEquals(1, fn("0xffff8a3c1234").self)
        assertNull(profile.functions.find { it.name == "0x0" || it.name == "[unknown]" })
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
    fun aRecordingWithoutStacksHasOnlyOneFramePerSample() {
        val flat = PerfScript.parse("hot 1 1.0: 1 cpu-clock:\n\t 10 hot.main+0x0 (/tmp/prof/hot)\n\nhot 1 1.1: 1 cpu-clock:\n\t 20 hot.collatz+0x0 (/tmp/prof/hot)\n")
        assertEquals(2, flat.samples)
        // this is what PerfBackend checks before falling back to --call-graph dwarf
        assertEquals(true, flat.functions.none { it.total > it.self })
    }
}
