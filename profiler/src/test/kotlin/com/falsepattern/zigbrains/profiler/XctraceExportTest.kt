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
import org.junit.Test

/** Fixture: a trimmed real `xctrace export` of a ReleaseSafe zig program where `main` inlined `collatz`. */
class XctraceExportTest {
    private val profile = javaClass.getResourceAsStream("/xctrace-time-profile.xml")!!.use { XctraceExport.parse(it) }

    private fun fn(name: String) = profile.functions.single { it.name == name }

    @Test
    fun countsEveryRowAsASample() {
        assertEquals(21, profile.samples)
    }

    @Test
    fun selfIsTheLeafFrameAndRefsResolveToTheSameFunction() {
        assertEquals(11, fn("hot.collatz").self)
        assertEquals(7, fn("hot.sieve").self)
        assertEquals(1, fn("hot.main").self)
    }

    @Test
    fun totalCountsEveryFrameOnTheStack() {
        // both dyld stacks and all zig stacks bottom out in `start`, under different addresses
        assertEquals(0, fn("start").self)
        assertEquals(21, fn("start").total)
        assertEquals(11, fn("hot.collatz").total)
    }

    @Test
    fun keepsTheFirstSourceLocation() {
        assertEquals("/tmp/prof/hot.zig", fn("hot.sieve").file)
        assertEquals(16, fn("hot.sieve").line)
        assertEquals(null, fn("start").file)
    }

    @Test
    fun sortsBySelfDescending() {
        assertEquals(listOf("hot.collatz", "hot.sieve"), profile.functions.take(2).map { it.name })
    }

    @Test
    fun execPathDropsSamplesFromOtherProcesses() {
        // a --all-processes recording (repeat mode) also samples dyld startup, with no `hot` frame at all
        val filtered = javaClass.getResourceAsStream("/xctrace-time-profile.xml")!!.use { XctraceExport.parse(it, "/tmp/prof/hot") }
        assertEquals(19, filtered.samples)
        assertEquals(11, filtered.functions.single { it.name == "hot.collatz" }.self)
    }

    @Test
    fun execPathMatchingNothingLeavesNoSamples() {
        val filtered = javaClass.getResourceAsStream("/xctrace-time-profile.xml")!!.use { XctraceExport.parse(it, "/tmp/prof/does-not-exist") }
        assertEquals(0, filtered.samples)
        assertEquals(emptyList<FunctionStats>(), filtered.functions)
    }

    @Test
    fun recursionCountsOncePerSampleAndEmptyRowsAreNoSamples() {
        val xml = """
            <trace-query-result><node>
            <row><backtrace id="1"><frame id="2" name="fib.fib" addr="0x10"/><frame ref="2"/><frame id="3" name="" addr="0x20"/></backtrace></row>
            <row><backtrace ref="1"/></row>
            <row><sample-time>1</sample-time></row>
            <row><backtrace/></row>
            </node></trace-query-result>
        """.trimIndent()
        val p = XctraceExport.parse(xml.byteInputStream())
        assertEquals(2, p.samples)
        val fib = p.functions.single { it.name == "fib.fib" }
        assertEquals(2, fib.self)
        assertEquals(2, fib.total)
        // a frame without a symbol name falls back to its address
        assertEquals(2, p.functions.single { it.name == "0x20" }.total)
    }
}
