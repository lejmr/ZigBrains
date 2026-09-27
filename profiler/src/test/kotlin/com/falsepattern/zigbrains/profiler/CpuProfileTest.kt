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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dwarf fallback decision of [CpuProfile.needsDwarf], driven by how many samples carry a caller. */
class CpuProfileTest {
    private val flat = Sample(listOf(Frame("leaf")))
    private val stacked = Sample(listOf(Frame("leaf"), Frame("caller")))

    private fun profile(vararg samples: Sample) = CpuProfile.of(samples.asSequence())

    @Test
    fun anEmptyRecordingDoesNotAskForDwarf() {
        assertFalse(profile().needsDwarf())
        // frame-less samples do not count either
        assertFalse(profile(Sample(emptyList())).needsDwarf())
    }

    @Test
    fun oneFrameStacksAskForDwarf() {
        assertTrue(profile(flat).needsDwarf())
        assertTrue(profile(flat, flat, flat, stacked).needsDwarf())
    }

    @Test
    fun halfOrMoreStackedIsGoodEnough() {
        assertFalse(profile(flat, stacked).needsDwarf())
        assertFalse(profile(flat, stacked, stacked).needsDwarf())
        assertEquals(2, profile(flat, stacked, stacked).stacked)
    }
}
