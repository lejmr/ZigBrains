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

/**
 * Fixture shape is copied from the real output of the fixed `builder.zig` helper run against a 3-package
 * monorepo (root + path dependencies "libcalc" and "cli", "cli" importing "libcalc"'s "calc" module):
 * project 0 = root (no modules of its own), project 1 = libcalc (module 0 = "calc", no imports),
 * project 2 = cli (module 0 = "main", imports "calc" from project 1 module 0). Module roots are absolute,
 * as the fixed helper now guarantees.
 */
class ZigTestModulesTest {
    private val root = "/repo"

    private val libcalcModule = Serialization.Module(root = "$root/libcalc/src/calc.zig", public = true, imports = emptyList())
    private val cliModule = Serialization.Module(
        root = "$root/cli/src/main.zig",
        public = false,
        imports = listOf(Serialization.Import(name = "calc", owner = 1, module = 0)),
    )

    private val rootProject = Serialization.Project(name = "monorepo", version = null, path = root, steps = emptyList(), modules = emptyList(), dependencies = listOf(Serialization.Dependency(1, false), Serialization.Dependency(2, false)))
    private val libcalcProject = Serialization.Project(name = "libcalc", version = null, path = "$root/libcalc", steps = emptyList(), modules = listOf(libcalcModule), dependencies = emptyList())
    private val cliProject = Serialization.Project(name = "cli", version = null, path = "$root/cli", steps = emptyList(), modules = listOf(cliModule), dependencies = listOf(Serialization.Dependency(1, false)))

    private val projects = listOf(rootProject, libcalcProject, cliProject)

    @Test
    fun cliMainImportingLibcalcGetsDepAndModuleFlags() {
        val args = ZigTestModules.moduleArgs(projects, Path.of("$root/cli/src/main.zig"))

        assertEquals(
            listOf("--dep", "calc=calc", "-Mroot=$root/cli/src/main.zig", "-Mcalc=$root/libcalc/src/calc.zig"),
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
    fun moduleWithRelativeRootIsIgnored() {
        // C1: an unfixed/pre-fix scan (or a module builder.zig couldn't resolve) reports a relative root;
        // such a module can't be an ancestor of anything, so the file falls back to plain `test <file>`.
        val relativeCliModule = cliModule.copy(root = "cli/src/main.zig")
        val projectsWithRelativeRoot = listOf(rootProject, libcalcProject, cliProject.copy(modules = listOf(relativeCliModule)))

        val args = ZigTestModules.moduleArgs(projectsWithRelativeRoot, Path.of("$root/cli/src/main.zig"))

        assertNull(args)
    }

    @Test
    fun diamondImportIsNotDuplicated() {
        // both "cli"'s main module and a second "extra" module import the same "calc" module; it must
        // only get one -M line, but each importer still declares its own --dep.
        val extraModule = Serialization.Module(
            root = "$root/cli/src/extra.zig",
            public = false,
            imports = listOf(Serialization.Import(name = "calc", owner = 1, module = 0)),
        )
        val cliWithExtra = cliModule.copy(
            imports = listOf(
                Serialization.Import(name = "calc", owner = 1, module = 0),
                Serialization.Import(name = "extra", owner = 2, module = 1),
            ),
        )
        val diamondProjects = listOf(rootProject, libcalcProject, cliProject.copy(modules = listOf(cliWithExtra, extraModule)))

        val args = ZigTestModules.moduleArgs(diamondProjects, Path.of("$root/cli/src/main.zig"))

        assertEquals(
            listOf(
                "--dep", "calc=calc",
                "--dep", "extra=extra",
                "-Mroot=$root/cli/src/main.zig",
                "-Mcalc=$root/libcalc/src/calc.zig",
                "--dep", "calc=calc",
                "-Mextra=$root/cli/src/extra.zig",
            ),
            args,
        )
    }

    @Test
    fun selfImportResolvesBackToRootInsteadOfDuplicating() {
        // a module that imports itself (directly, or, as here, the common "expose my own root as a named
        // module" pattern) must not get a second -M definition for the same file.
        val selfImportingModule = cliModule.copy(
            imports = listOf(Serialization.Import(name = "self", owner = 2, module = 0)),
        )
        val selfProjects = listOf(rootProject, libcalcProject, cliProject.copy(modules = listOf(selfImportingModule)))

        val args = ZigTestModules.moduleArgs(selfProjects, Path.of("$root/cli/src/main.zig"))

        assertEquals(
            listOf("--dep", "self=root", "-Mroot=$root/cli/src/main.zig"),
            args,
        )
    }

    @Test
    fun aliasCollisionGetsASuffixedModuleName() {
        // two distinct imported modules both happen to be imported under the source-visible name "calc";
        // the second one's -M name must not collide with the first's.
        val otherCalcModule = Serialization.Module(root = "$root/other/src/calc.zig", public = true, imports = emptyList())
        val twoCalcsModule = cliModule.copy(
            imports = listOf(
                Serialization.Import(name = "calc", owner = 1, module = 0),
                Serialization.Import(name = "calc", owner = 3, module = 0),
            ),
        )
        val otherProject = Serialization.Project(name = "other", version = null, path = "$root/other", steps = emptyList(), modules = listOf(otherCalcModule), dependencies = emptyList())
        val aliasProjects = listOf(rootProject, libcalcProject, cliProject.copy(modules = listOf(twoCalcsModule)), otherProject)

        val args = ZigTestModules.moduleArgs(aliasProjects, Path.of("$root/cli/src/main.zig"))

        assertEquals(
            listOf(
                "--dep", "calc=calc",
                "--dep", "calc=calc2",
                "-Mroot=$root/cli/src/main.zig",
                "-Mcalc=$root/libcalc/src/calc.zig",
                "-Mcalc2=$root/other/src/calc.zig",
            ),
            args,
        )
    }

    @Test
    fun zigInitSiblingLayoutPrefersTheFileItself() {
        // `zig init`'s default layout: src/root.zig (a library module) and src/main.zig (an executable
        // module) share the same directory. Without an exact-file match, the tie would otherwise be
        // arbitrary; testing main.zig must not silently pick up root.zig's module instead.
        val libModule = Serialization.Module(root = "$root/init/src/root.zig", public = true, imports = emptyList())
        val exeModule = Serialization.Module(
            root = "$root/init/src/main.zig",
            public = false,
            imports = listOf(Serialization.Import(name = "calc", owner = 1, module = 0)),
        )
        val initProject = Serialization.Project(name = "init", version = null, path = "$root/init", steps = emptyList(), modules = listOf(libModule, exeModule), dependencies = emptyList())
        val initProjects = listOf(rootProject, libcalcProject, initProject)

        val args = ZigTestModules.moduleArgs(initProjects, Path.of("$root/init/src/main.zig"))

        assertEquals(
            listOf("--dep", "calc=calc", "-Mroot=$root/init/src/main.zig", "-Mcalc=$root/libcalc/src/calc.zig"),
            args,
        )
    }

    @Test
    fun buildCommandLineArgsOrdersOptimizationBeforeModuleArgs() {
        // per-module options (-O, compiler args) apply to whichever -M/file comes right after them, so
        // they must precede the module args, not follow -- a trailing -O is silently ignored by zig.
        val args = ZigExecConfigTest.assembleTestArgs(
            debug = false,
            forceOptimization = false,
            optimizationLevel = "ReleaseSafe",
            compilerArgs = listOf("-fno-emit-bin"),
            moduleArgs = listOf("--dep", "calc=calc", "-Mroot=$root/cli/src/main.zig", "-Mcalc=$root/libcalc/src/calc.zig"),
            fileArg = null,
        )

        assertEquals(
            listOf(
                "test", "-O", "ReleaseSafe", "-fno-emit-bin",
                "--dep", "calc=calc", "-Mroot=$root/cli/src/main.zig", "-Mcalc=$root/libcalc/src/calc.zig",
            ),
            args,
        )
    }

    @Test
    fun buildCommandLineArgsPlainFileFallbackKeepsOrderAndAppendsTestNoExecForDebug() {
        val args = ZigExecConfigTest.assembleTestArgs(
            debug = true,
            forceOptimization = true,
            optimizationLevel = "Debug",
            compilerArgs = emptyList(),
            moduleArgs = null,
            fileArg = "$root/standalone.zig",
        )

        assertEquals(
            listOf("test", "-O", "Debug", "$root/standalone.zig", "--test-no-exec"),
            args,
        )
    }
}
