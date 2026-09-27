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

import com.falsepattern.zigbrains.project.buildscan.Serialization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

/** Fixture mirrors /Users/milos/src/tries/2026-09-27-zb-monorepo: a root package with two path
 *  dependencies, "cli" (index 1) importing the "calc" module owned by "libcalc" (index 2). */
class ZigTestModulesTest {
    private val root = "/Users/milos/src/tries/2026-09-27-zb-monorepo"

    private val libcalcModule = Serialization.Module(root = "$root/libcalc/src/calc.zig", public = true, imports = emptyList())
    private val cliModule = Serialization.Module(
        root = "$root/cli/src/main.zig",
        public = false,
        imports = listOf(Serialization.Import(name = "calc", owner = 2, module = 0)),
    )

    private val projects = listOf(
        Serialization.Project(name = "zb-monorepo", version = null, path = root, steps = emptyList(), modules = emptyList(), dependencies = listOf(Serialization.Dependency(1, false), Serialization.Dependency(2, false))),
        Serialization.Project(name = "cli", version = null, path = "$root/cli", steps = emptyList(), modules = listOf(cliModule), dependencies = listOf(Serialization.Dependency(2, false))),
        Serialization.Project(name = "libcalc", version = null, path = "$root/libcalc", steps = emptyList(), modules = listOf(libcalcModule), dependencies = emptyList()),
    )

    @Test
    fun cliMainImportingLibcalcGetsDepAndModuleFlags() {
        val args = ZigTestModules.moduleArgs(projects, Path.of("$root/cli/src/main.zig"))

        assertEquals(
            listOf("--dep", "calc", "-Mroot=$root/cli/src/main.zig", "-Mcalc=$root/libcalc/src/calc.zig"),
            args,
        )
    }

    @Test
    fun moduleWithoutImportsFallsBackToPlainFile() {
        val args = ZigTestModules.moduleArgs(projects, Path.of("$root/libcalc/src/calc.zig"))

        assertNull(args)
    }

    @Test
    fun fileOutsideAnyScannedProjectFallsBackToPlainFile() {
        val args = ZigTestModules.moduleArgs(projects, Path.of("/tmp/somewhere/else.zig"))

        assertNull(args)
    }

    @Test
    fun diamondImportIsNotDuplicated() {
        // both "cli" and a second module "extra" import the same "calc" module; it must only get one -M line.
        val extraModule = Serialization.Module(
            root = "$root/cli/src/extra.zig",
            public = false,
            imports = listOf(Serialization.Import(name = "calc", owner = 2, module = 0)),
        )
        val cliWithExtra = Serialization.Module(
            root = "$root/cli/src/main.zig",
            public = false,
            imports = listOf(
                Serialization.Import(name = "calc", owner = 2, module = 0),
                Serialization.Import(name = "extra", owner = 1, module = 1),
            ),
        )
        val diamondProjects = listOf(
            projects[0],
            projects[1].copy(modules = listOf(cliWithExtra, extraModule)),
            projects[2],
        )

        val args = ZigTestModules.moduleArgs(diamondProjects, Path.of("$root/cli/src/main.zig"))

        assertEquals(
            listOf(
                "--dep", "calc",
                "--dep", "extra",
                "-Mroot=$root/cli/src/main.zig",
                "-Mcalc=$root/libcalc/src/calc.zig",
                "--dep", "calc",
                "-Mextra=$root/cli/src/extra.zig",
            ),
            args,
        )
    }
}
