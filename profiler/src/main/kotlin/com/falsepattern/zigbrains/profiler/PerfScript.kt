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
 * Reads the default `perf script` output of a `perf record -g` run into a [CpuProfile]:
 * a header line per sample (`comm pid time: period event:`), then one indented line per
 * frame, innermost first (`addr symbol+0xoff (dso)`), then a blank line.
 */
object PerfScript {
    // the dso group takes no parentheses, so a symbol like `(anonymous namespace)::f(int)` keeps them
    private val frameLine = Regex("""\s+([0-9a-fA-F]+)\s+(.*?)(?:\+0x[0-9a-fA-F]+)?\s+\(([^()]*)\)\s*""")

    fun parse(text: String): CpuProfile = CpuProfile.of(sequence {
        var frames: MutableList<Frame>? = null
        for (line in text.lineSequence()) {
            val m = frameLine.matchEntire(line)
            when {
                m != null && frames != null -> {
                    val (addr, symbol, dso) = m.destructured
                    // a frame-pointer chain ends in a zero return address that is no function
                    if (addr.all { it == '0' }) continue
                    // no symbol (stripped library, JIT): one row per binary rather than one per address
                    val name = when {
                        symbol.isNotEmpty() && symbol != "[unknown]" -> symbol
                        dso.isEmpty() || dso == "[unknown]" -> "[unknown]"
                        else -> "[unknown] (${dso.substringAfterLast('/')})"
                    }
                    frames.add(Frame(name))
                }
                line.isBlank() -> {
                    frames?.let { yield(Sample(it)) }
                    frames = null
                }
                // a header starts at column 0 (the comm is not padded in callchain output), frames are indented;
                // a header right after another one was a frame-less sample
                !line.startsWith(' ') && !line.startsWith('\t') -> {
                    frames?.let { yield(Sample(it)) }
                    frames = ArrayList()
                }
            }
        }
        frames?.let { yield(Sample(it)) }
    })
}
