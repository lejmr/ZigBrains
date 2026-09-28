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

package com.falsepattern.zigbrains.zig.references

import com.falsepattern.zigbrains.project.buildscan.Serialization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class ZigImportResolutionTest {
    @Test
    fun `a dot-zig path resolves relative to the importing file, no build scan needed`() {
        val result = ZigImportResolution.resolve(emptyList(), Path.of("/proj/src/main.zig"), "helper.zig")
        assertEquals(Path.of("/proj/src/helper.zig"), result)
    }

    @Test
    fun `a bare name resolves through the owning module's import graph`() {
        val projects = listOf(
            Serialization.Project(
                name = "root", version = null, path = "/proj",
                steps = emptyList(),
                modules = listOf(
                    Serialization.Module(
                        root = "/proj/src/main.zig", public = false,
                        imports = listOf(Serialization.Import(name = "calc", owner = 0, module = 1)),
                    ),
                    Serialization.Module(root = "/proj/libcalc/calc.zig", public = true, imports = emptyList()),
                ),
                dependencies = emptyList(),
            )
        )
        val result = ZigImportResolution.resolve(projects, Path.of("/proj/src/main.zig"), "calc")
        assertEquals(Path.of("/proj/libcalc/calc.zig"), result)
    }

    @Test
    fun `an unknown import name resolves to nothing`() {
        assertNull(ZigImportResolution.resolve(emptyList(), Path.of("/proj/src/main.zig"), "calc"))
    }
}
