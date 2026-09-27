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

import java.io.ByteArrayOutputStream

/**
 * Pure Kotlin: finds the test binaries a `zig build <step> --verbose` run compiled, from the command lines the
 * build runner prints to stderr before spawning each child (`lib/std/Build/Step.zig`, `handleVerbose2`).
 *
 * A test artifact shows up as `<zig> test ... -Mroot=<file> ... --listen=-`; the build runner then runs the
 * emitted binary as `.zig-cache/o/<hash>/test --listen=-` and talks to it over that protocol, printing only
 * failures. Re-running the compile line *without* `--listen=-` is a plain `zig test`: the compilation is a
 * cache hit and the test runner, seeing a pipe, prints one `N/M name...OK` line per test -- the same output
 * the per-file test configuration already parses -- and the root source file is right there in `-Mroot=`.
 * The run lines are not used: the binary path alone cannot be paired with its source file.
 */
object ZigBuildTestDiscovery {
    /** One `zig test` compile the build runner spawned: its argv minus `--listen=-`, and its root source file. */
    data class TestCompile(val argv: List<String>, val rootFile: String?)

    /**
     * True when [args] (the configuration's build steps and extra args, already split) look like a finite test
     * run: a step with `test` as a word (`test`, `test-unit`, `unit_test`, `lib:test`), and none of the build
     * runner's long-running modes (`--watch`, `--webui`, `--fuzz`, `--debounce`), which never exit and need the
     * PTY console.
     */
    fun runsTests(args: List<String>): Boolean {
        if (args.any { it == "--watch" || it == "--webui" || it.startsWith("--webui=") || it == "--fuzz" || it == "--debounce" }) return false
        return args.any { !it.startsWith("-") && TEST_STEP.containsMatchIn(it) }
    }

    fun testCompiles(lines: List<String>): List<TestCompile> = lines.mapNotNull { testCompile(it) }

    fun testCompile(line: String): TestCompile? {
        if (!line.contains(LISTEN)) return null
        val argv = splitVerboseLine(line)
        // `cd <dir> && ...` (a Run step with a cwd) and `zig build-exe ...` lines fall out here.
        if (argv.size < 3 || argv[1] != "test" || !argv.contains(LISTEN)) return null
        val rootFile = argv.firstOrNull { it.startsWith(ROOT_MODULE) }?.removePrefix(ROOT_MODULE)
            // older build runners passed the root source file positionally
            ?: argv.drop(2).firstOrNull { !it.startsWith("-") && it.endsWith(".zig") }
        return TestCompile(argv.filter { it != LISTEN }, rootFile)
    }

    /**
     * Inverse of zig's `allocPrintCmd` shell escaping: space-separated words; a word with anything outside
     * `[%+,-./0-9:@A-Za-z_=]` is wrapped in double quotes, with `!"$\`` backslash-escaped, C escapes for
     * control characters and `\NNN` octal for every other byte (so non-ASCII UTF-8 arrives byte by byte).
     */
    fun splitVerboseLine(line: String): List<String> {
        val words = ArrayList<String>()
        var i = 0
        while (i < line.length) {
            if (line[i] == ' ') {
                i++
                continue
            }
            if (line[i] != '"') {
                val end = line.indexOf(' ', i).let { if (it < 0) line.length else it }
                words += line.substring(i, end)
                i = end
                continue
            }
            val bytes = ByteArrayOutputStream()
            i++
            while (i < line.length && line[i] != '"') {
                var c = line[i]
                if (c == '\\' && i + 1 < line.length) {
                    i++
                    c = line[i]
                    if (c in '0'..'7') {
                        val end = minOf(i + 3, line.length)
                        var digits = i
                        while (digits < end && line[digits] in '0'..'7') digits++
                        bytes.write(line.substring(i, digits).toInt(8))
                        i = digits
                        continue
                    }
                    c = when (c) {
                        'a' -> '\u0007'
                        'b' -> '\b'
                        't' -> '\t'
                        'n' -> '\n'
                        'v' -> '\u000B'
                        'f' -> '\u000C'
                        'r' -> '\r'
                        'E' -> '\u001B'
                        else -> c
                    }
                }
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            i++
            words += bytes.toString(Charsets.UTF_8)
        }
        return words
    }

    const val LISTEN = "--listen=-"
    private const val ROOT_MODULE = "-Mroot="
    // `test` at the start or after a separator: `test`, `tests`, `test-unit`, `unit_test`, `lib:test`, not `latest`.
    private val TEST_STEP = Regex("""(^|[-_:])test""")
}
