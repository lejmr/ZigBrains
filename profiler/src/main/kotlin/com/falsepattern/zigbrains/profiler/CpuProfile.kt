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

/** One stack frame; [file]/[line] only when the profiler had debug info for it. */
data class Frame(val name: String, val file: String? = null, val line: Int? = null)

/** One CPU sample; [frames] are innermost first. */
class Sample(val frames: List<Frame>)

/** Sample counts of one function; [file]/[line] come from the first frame of it that carried debug info. */
data class FunctionStats(val name: String, val self: Int, val total: Int, val file: String?, val line: Int?)

/**
 * A flat self/total profile, the same shape whichever profiler recorded it.
 * [samples] counts only samples with at least one frame, so it is the denominator for Self %.
 */
class CpuProfile(val samples: Int, val functions: List<FunctionStats>) {
    companion object {
        fun of(samples: Sequence<Sample>): CpuProfile {
            val self = HashMap<String, Int>()
            val total = HashMap<String, Int>()
            val source = HashMap<String, Pair<String, Int>>()
            var count = 0
            for (sample in samples) {
                val frames = sample.frames
                if (frames.isEmpty()) continue
                count++
                self.merge(frames.first().name, 1, Int::plus)
                for (f in frames) if (f.file != null && f.line != null) source.putIfAbsent(f.name, f.file to f.line)
                // a recursive function counts once per sample in its total
                frames.mapTo(HashSet()) { it.name }.forEach { total.merge(it, 1, Int::plus) }
            }
            val functions = total.map { (name, t) ->
                FunctionStats(name, self[name] ?: 0, t, source[name]?.first, source[name]?.second)
            }.sortedWith(compareByDescending<FunctionStats> { it.self }.thenByDescending { it.total })
            return CpuProfile(count, functions)
        }
    }
}
