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

package com.falsepattern.zigbrains.project.execution.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ZigExecConfigTest.assembleTestArgs]: `-O` and the compiler args apply to the module that follows them. */
class ZigExecConfigTestArgsTest {
    private val moduleArgs = listOf("--dep", "helper", "-Mroot=/p/root.zig", "-Mhelper=/p/helper.zig")

    @Test
    fun plainFileRunPutsOptimizationAndFilterBeforeTheFile() {
        assertEquals(
            listOf("test", "-O", "ReleaseSafe", "--test-filter", "adds", "/p/root.zig"),
            ZigExecConfigTest.assembleTestArgs(false, false, "ReleaseSafe", listOf("--test-filter", "adds"), null, "/p/root.zig"),
        )
    }

    @Test
    fun moduleRunPutsOptimizationBeforeTheFirstModuleArg() {
        val args = ZigExecConfigTest.assembleTestArgs(false, false, "Debug", emptyList(), moduleArgs, null)
        assertEquals(listOf("test", "-O", "Debug") + moduleArgs, args)
        assertTrue(args.indexOf("-O") < args.indexOfFirst { it.startsWith("-M") })
    }

    @Test
    fun debugRunSkipsOptimizationAndOnlyCompiles() {
        assertEquals(
            listOf("test") + moduleArgs + "--test-no-exec",
            ZigExecConfigTest.assembleTestArgs(true, false, "ReleaseFast", emptyList(), moduleArgs, null),
        )
    }

    @Test
    fun forcedOptimizationSurvivesADebugRun() {
        assertEquals(
            listOf("test", "-O", "ReleaseFast", "/p/root.zig", "--test-no-exec"),
            ZigExecConfigTest.assembleTestArgs(true, true, "ReleaseFast", emptyList(), null, "/p/root.zig"),
        )
    }
}
