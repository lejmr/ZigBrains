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
class XctraceProfileTest {
    private val profile = javaClass.getResourceAsStream("/xctrace-time-profile.xml")!!.use { XctraceProfile.parse(it) }

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
}
