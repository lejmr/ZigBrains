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
 * One node of the call tree, keyed by the call path from the outermost frame down (not just by function
 * name): the same function reached through two different callers is two different nodes, each with its own
 * self/total, exactly what the flat [FunctionStats] list collapses away. [file]/[line] are this specific call
 * site's, not just wherever the function happens to be declared.
 */
class CallTreeNode(val name: String, val file: String?, val line: Int?) {
    var self: Int = 0
        internal set
    var total: Int = 0
        internal set
    val children = LinkedHashMap<String, CallTreeNode>()
}

/**
 * A flat self/total profile, the same shape whichever profiler recorded it.
 * [samples] counts only samples with at least one frame, so it is the denominator for Self %;
 * [stacked] is how many of those had a caller too.
 */
class CpuProfile(val samples: Int, val stacked: Int, val functions: List<FunctionStats>, val callTree: CallTreeNode) {
    /** Most samples came without a call stack, so the binary likely lacks frame pointers; an empty recording is no reason to retry. */
    fun needsDwarf(): Boolean = samples > 0 && stacked * 2 < samples

    companion object {
        fun of(samples: Sequence<Sample>): CpuProfile {
            val self = HashMap<String, Int>()
            val total = HashMap<String, Int>()
            val source = HashMap<String, Pair<String, Int>>()
            val root = CallTreeNode("", null, null)
            var count = 0
            var stacked = 0
            for (sample in samples) {
                val frames = sample.frames
                if (frames.isEmpty()) continue
                count++
                if (frames.size > 1) stacked++
                self.merge(frames.first().name, 1, Int::plus)
                for (f in frames) if (f.file != null && f.line != null) source.putIfAbsent(f.name, f.file to f.line)
                // a recursive function counts once per sample in its total
                frames.mapTo(HashSet()) { it.name }.forEach { total.merge(it, 1, Int::plus) }
                // the call tree walks outermost-first (the reverse of the flat aggregation above), so the
                // root's children are entry points and each node's own total already excludes siblings
                var node = root
                for (f in frames.asReversed()) {
                    node = node.children.getOrPut(f.name) { CallTreeNode(f.name, f.file, f.line) }
                    node.total++
                }
                node.self++
            }
            val functions = total.map { (name, t) ->
                FunctionStats(name, self[name] ?: 0, t, source[name]?.first, source[name]?.second)
            }.sortedWith(compareByDescending<FunctionStats> { it.self }.thenByDescending { it.total })
            return CpuProfile(count, stacked, functions, root)
        }
    }
}
