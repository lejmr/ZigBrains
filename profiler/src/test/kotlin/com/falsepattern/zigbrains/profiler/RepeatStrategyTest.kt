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

/** The pure loop/merge decisions of [RepeatStrategy], both the iterative (xctrace) and up-front (perf) forms. */
class RepeatStrategyTest {
    @Test
    fun aRunAtOrPastTheThresholdStopsTheLoop() {
        assertFalse(RepeatStrategy.shouldRunAgain(1, 2_000_000_000L, 2_000_000_000L))
        assertFalse(RepeatStrategy.shouldRunAgain(1, 1_000_000_000L, 1_000_000_000L))
        assertEquals(1, RepeatStrategy.loopCount(2_000_000_000L))
        assertEquals(1, RepeatStrategy.loopCount(1_000_000_000L))
    }

    @Test
    fun aFastRunRepeatsUntilTheTargetWallTimeIsCollected() {
        assertTrue(RepeatStrategy.shouldRunAgain(1, 10_000_000L, 10_000_000L))
        // one nanosecond short of the target still goes again, the target itself stops the loop
        assertTrue(RepeatStrategy.shouldRunAgain(50, 999_999_999L, 10_000_000L))
        assertFalse(RepeatStrategy.shouldRunAgain(50, 1_000_000_000L, 10_000_000L))
        // 1s / 10ms rounded up
        assertEquals(100, RepeatStrategy.loopCount(10_000_000L))
    }

    @Test
    fun theRunCountCapsForATinyProgram() {
        // one short of the cap still goes again
        assertTrue(RepeatStrategy.shouldRunAgain(499, 100_000_000L, 1_000L))
        // at the cap, no more
        assertFalse(RepeatStrategy.shouldRunAgain(500, 100_000_000L, 1_000L))
        assertEquals(500, RepeatStrategy.loopCount(1_000L))
    }

    @Test
    fun loopCountIsNeverLessThanOne() {
        assertEquals(1, RepeatStrategy.loopCount(0))
        assertEquals(1, RepeatStrategy.loopCount(-5))
    }
}
