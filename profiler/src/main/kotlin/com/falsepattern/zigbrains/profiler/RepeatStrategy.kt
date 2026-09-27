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

    /**
     * Iterative form (xctrace, which runs the program itself once per iteration): whether to run it once more,
     * given what the runs so far collected. perf instead sizes its own loop at runtime, in [repeatLoopScript]:
     * a calibration run's `perf record`/`perf script` overhead would dwarf a sub-millisecond program's own time.
     */
    fun shouldRunAgain(runsSoFar: Int, elapsedNanosSoFar: Long, lastRunNanos: Long): Boolean =
        lastRunNanos < TARGET_NANOS && runsSoFar < MAX_RUNS && elapsedNanosSoFar < TARGET_NANOS
}
