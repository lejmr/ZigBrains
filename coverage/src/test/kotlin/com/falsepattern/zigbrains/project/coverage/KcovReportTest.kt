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

package com.falsepattern.zigbrains.project.coverage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/**
 * Fixture is a real kcov 43 report: `zig test cov.zig --test-cmd kcov --test-cmd --include-path=<dir>
 * --test-cmd <out> --test-cmd-bin` (zig 0.16, macOS), with the scratch dir replaced by `/project`.
 */
class KcovReportTest {
    private val fixture = javaClass.getResourceAsStream("/kcov/cobertura.xml")!!.readBytes()
    private val expected = mapOf("/project/cov.zig" to mapOf(3 to 2, 4 to 1, 5 to 1, 7 to 1, 10 to 1, 11 to 1))

    @Test
    fun parsesRealReport() {
        assertEquals(expected, KcovReport.parse(fixture.inputStream()))
    }

    @Test
    fun restoresLeadingSlashDroppedWithoutIncludePath() {
        val xml = """<coverage><packages><package><classes><class filename="tmp/a.zig"><lines><line number="2" hits="0"/></lines></class></classes></package></packages></coverage>"""
        assertEquals(mapOf("/tmp/a.zig" to mapOf(2 to 0)), KcovReport.parse(xml.byteInputStream()))
    }

    @Test
    fun sumsPerBinaryReportsAndSkipsTheSymlinkAlias() {
        val out = Files.createTempDirectory("kcov")
        for (binary in listOf("test.1111", "test.2222")) {
            out.resolve(binary).createDirectories().resolve("cobertura.xml").writeBytes(fixture)
        }
        Files.createSymbolicLink(out.resolve("test"), out.resolve("test.2222"))
        out.resolve("kcov-merged").createDirectories()
        val doubled = expected.mapValues { (_, lines) -> lines.mapValues { it.value * 2 } }
        assertEquals(doubled, KcovReport.load(out))
    }

    @Test
    fun prefersMergedReport() {
        val out = Files.createTempDirectory("kcov")
        out.resolve("test.1111").createDirectories().resolve("cobertura.xml").writeBytes(fixture)
        out.resolve("kcov-merged").createDirectories().resolve("cobertura.xml").writeBytes(fixture)
        assertEquals(expected, KcovReport.load(out))
    }
}
