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

/**
 * How many times a short test binary is re-run under the profiler so it leaves behind a readable number of
 * samples instead of the handful a sub-millisecond program gets from a single pass.
 */
object RepeatStrategy {
    /** Keep looping until the runs so far cover this much wall time; also the per-run cutoff in [shouldRunAgain] below. */
    const val TARGET_NANOS = 1_000_000_000L

    /** Stop looping after this many runs even short of [TARGET_NANOS] (a program cheap enough to outrun the budget). */
    const val MAX_RUNS = 500

    /** A single `--launch` run with fewer samples than this was too short to profile meaningfully; worth repeating. */
    const val MIN_SAMPLES_BEFORE_REPEAT = 20

    /** Iterative form (xctrace): whether to run the program once more, given what the runs so far collected. */
    fun shouldRunAgain(runsSoFar: Int, elapsedNanosSoFar: Long, lastRunNanos: Long): Boolean =
        lastRunNanos < TARGET_NANOS && runsSoFar < MAX_RUNS && elapsedNanosSoFar < TARGET_NANOS

    /** Up-front form (perf): how many times to loop a program whose one recorded run took [runNanos]. */
    fun loopCount(runNanos: Long): Int {
        if (runNanos <= 0 || runNanos >= TARGET_NANOS) return 1
        val runs = (TARGET_NANOS + runNanos - 1) / runNanos // ceil division
        return runs.coerceIn(1, MAX_RUNS.toLong()).toInt()
    }
}
