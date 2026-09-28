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

/** [CpuProfile.callTree] keeps a function reached through two different callers as two separate nodes,
 *  the exact information the flat [CpuProfile.functions] list collapses away. */
class CallTreeTest {
    @Test
    fun sameFunctionThroughTwoCallersIsTwoNodes() {
        val leaf = Frame("leaf", "a.zig", 10)
        val callerA = Frame("callerA", "a.zig", 5)
        val callerB = Frame("callerB", "b.zig", 7)
        // innermost first, as every real backend hands them
        val profile = CpuProfile.of(
            sequenceOf(
                Sample(listOf(leaf, callerA)),
                Sample(listOf(leaf, callerA)),
                Sample(listOf(leaf, callerB)),
            )
        )

        val root = profile.callTree
        assertEquals(setOf("callerA", "callerB"), root.children.keys)

        val underA = root.children.getValue("callerA").children.getValue("leaf")
        assertEquals(2, underA.self)
        assertEquals(2, underA.total)
        assertEquals("a.zig" to 10, underA.file to underA.line)

        val underB = root.children.getValue("callerB").children.getValue("leaf")
        assertEquals(1, underB.self)
        assertEquals(1, underB.total)

        // the flat view still merges both call paths into one row, unlike the tree
        val flat = profile.functions.single { it.name == "leaf" }
        assertEquals(3, flat.self)
        assertEquals(3, flat.total)
    }

    @Test
    fun recursionNestsRatherThanLoops() {
        val leaf = Frame("recurse")
        val profile = CpuProfile.of(sequenceOf(Sample(listOf(leaf, leaf, leaf))))

        val level1 = profile.callTree.children.getValue("recurse")
        val level2 = level1.children.getValue("recurse")
        val level3 = level2.children.getValue("recurse")
        assertEquals(1, level1.total)
        assertEquals(1, level2.total)
        assertEquals(1, level3.self)
        assertNull(level3.children["recurse"])
    }

    @Test
    fun emptyProfileHasAnEmptyTree() {
        val profile = CpuProfile.of(emptySequence())
        assertEquals(emptySet<String>(), profile.callTree.children.keys)
    }
}
