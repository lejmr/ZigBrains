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

package com.falsepattern.zigbrains.project.execution.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures were captured verbatim from `zig build test --verbose 2>&1` with zig 0.16.0 against a package
 *  with two `addTest` artifacts (one importing the other as module `util`); only the directories were renamed. */
class ZigBuildTestDiscoveryTest {
    private val zig = "/opt/zig/zig"
    private val lib = "/opt/zig/lib/"

    private val verboseRun = """
        $zig test -ODebug -Mroot=/home/user/pkg/src/util.zig --cache-dir .zig-cache --global-cache-dir /home/user/.cache/zig --name test --zig-lib-dir $lib --listen=-
        $zig test -ODebug --dep util -Mroot=/home/user/pkg/src/root.zig -ODebug -Mutil=/home/user/pkg/src/util.zig --cache-dir .zig-cache --global-cache-dir /home/user/.cache/zig --name test --zig-lib-dir $lib --listen=-
        ./.zig-cache/o/1b0df24d0e92fc6e19c2c5c2fc1488a0/test --cache-dir=./.zig-cache --seed=0x3507d4f7 --listen=-
        ./.zig-cache/o/8c9fcb6e607a411a8f94961d1f18add8/test --cache-dir=./.zig-cache --seed=0x3507d4f7 --listen=-
        test
        +- run test 3 pass, 1 fail (4 total)
        error: 'root.test.root fails on purpose so the tree has a red leaf' failed:
               expected 1, found 2
        failed command: ./.zig-cache/o/8c9fcb6e607a411a8f94961d1f18add8/test --cache-dir=./.zig-cache --seed=0x3507d4f7 --listen=-

        Build Summary: 3/5 steps succeeded (1 failed); 4/6 tests passed (1 skipped, 1 failed)
        test transitive failure
        +- run test 3 pass, 1 fail (4 total)

        error: the following build command failed with exit code 1:
        .zig-cache/o/80198607cdbb4b8304e7a6e50dc008ac/build $zig /opt/zig/lib /home/user/pkg .zig-cache /home/user/.cache/zig --seed 0x3507d4f7 -Z0595b82a22179b7d test --verbose
    """.trimIndent().split("\n")

    @Test
    fun everyTestCompileIsFoundWithItsRootFileAndWithoutListen() {
        val compiles = ZigBuildTestDiscovery.testCompiles(verboseRun)
        assertEquals(listOf("/home/user/pkg/src/util.zig", "/home/user/pkg/src/root.zig"), compiles.map { it.rootFile })
        assertEquals(
            listOf(
                zig, "test", "-ODebug", "--dep", "util", "-Mroot=/home/user/pkg/src/root.zig", "-ODebug",
                "-Mutil=/home/user/pkg/src/util.zig", "--cache-dir", ".zig-cache", "--global-cache-dir",
                "/home/user/.cache/zig", "--name", "test", "--zig-lib-dir", lib,
            ),
            compiles[1].argv,
        )
    }

    @Test
    fun runLinesSummaryAndBuildRunnerCommandAreNotTestCompiles() {
        // The emitted binaries' own `--listen=-` lines: argv[1] is not `test`.
        assertNull(ZigBuildTestDiscovery.testCompile("./.zig-cache/o/1b0df24d0e92fc6e19c2c5c2fc1488a0/test --cache-dir=./.zig-cache --seed=0x3507d4f7 --listen=-"))
        assertNull(ZigBuildTestDiscovery.testCompile("failed command: ./.zig-cache/o/8c9fcb6e607a411a8f94961d1f18add8/test --cache-dir=./.zig-cache --seed=0x3507d4f7 --listen=-"))
        // A non-test artifact and a Run step with a cwd, captured from a package with one addExecutable.
        assertNull(ZigBuildTestDiscovery.testCompile("$zig build-exe -Mroot=/home/user/exe/src/main.zig --cache-dir .zig-cache --global-cache-dir /home/user/.cache/zig --name hello --zig-lib-dir $lib --listen=-"))
        assertNull(ZigBuildTestDiscovery.testCompile("cd /home/user/exe/src && ./../.zig-cache/o/2cff461250f8f81d194a7ff886acdae7/hello"))
        assertNull(ZigBuildTestDiscovery.testCompile("Build Summary: 3/5 steps succeeded (1 failed); 4/6 tests passed (1 skipped, 1 failed)"))
    }

    @Test
    fun quotedArgumentsAreUnescaped() {
        // A package directory named `sp ace$x`: zig quotes the whole word and backslashes the dollar.
        val spaced = ZigBuildTestDiscovery.testCompile("""$zig test -ODebug "-Mroot=/home/user/sp ace\${'$'}x/src/util.zig" --cache-dir .zig-cache --name test --listen=-""")!!
        assertEquals("/home/user/sp ace\$x/src/util.zig", spaced.rootFile)
        assertEquals("-Mroot=/home/user/sp ace\$x/src/util.zig", spaced.argv[3])
        // A directory named `příliš`: every non-ASCII byte comes out as a three-digit octal escape.
        val czech = ZigBuildTestDiscovery.testCompile("""$zig test -ODebug "-Mroot=/home/user/p\305\231\303\255li\305\241/src/util.zig" --cache-dir .zig-cache --name test --listen=-""")!!
        assertEquals("/home/user/příliš/src/util.zig", czech.rootFile)
    }

    @Test
    fun controlCharactersAndQuotesInsideAQuotedWord() {
        assertEquals(
            listOf("a", "tab\there", "quote\"d", "new\nline", "back\\slash"),
            ZigBuildTestDiscovery.splitVerboseLine("""a "tab\there" "quote\"d" "new\nline" "back\\slash""""),
        )
    }

    @Test
    fun positionalRootFileIsTheFallbackWhenThereIsNoRootModuleFlag() {
        val compile = ZigBuildTestDiscovery.testCompile("$zig test -ODebug /home/user/pkg/src/main.zig --cache-dir .zig-cache --name test --listen=-")!!
        assertEquals("/home/user/pkg/src/main.zig", compile.rootFile)
        assertFalse(compile.argv.contains("--listen=-"))
    }

    @Test
    fun stepNamesThatRunTests() {
        assertTrue(ZigBuildTestDiscovery.runsTests(listOf("test")))
        assertTrue(ZigBuildTestDiscovery.runsTests(listOf("tests")))
        assertTrue(ZigBuildTestDiscovery.runsTests(listOf("install", "test-unit")))
        assertTrue(ZigBuildTestDiscovery.runsTests(listOf("unit_test", "-Doptimize=ReleaseSafe")))
        assertTrue(ZigBuildTestDiscovery.runsTests(listOf("lib:test")))
        assertFalse(ZigBuildTestDiscovery.runsTests(listOf("install", "run")))
        assertFalse(ZigBuildTestDiscovery.runsTests(listOf("latest")))
        assertFalse(ZigBuildTestDiscovery.runsTests(listOf("-Dtest-filter=foo")))
        assertFalse(ZigBuildTestDiscovery.runsTests(emptyList()))
    }

    @Test
    fun longRunningBuildModesKeepThePlainConsole() {
        // The fuzz gutter configuration: never exits, so no tree can be built from it.
        assertFalse(ZigBuildTestDiscovery.runsTests(listOf("test", "--fuzz", "--webui", "-Doptimize=ReleaseSafe")))
        assertFalse(ZigBuildTestDiscovery.runsTests(listOf("test", "--watch")))
        assertFalse(ZigBuildTestDiscovery.runsTests(listOf("test", "--webui=127.0.0.1:8080")))
        assertFalse(ZigBuildTestDiscovery.runsTests(listOf("test", "--debounce", "200")))
    }
}
