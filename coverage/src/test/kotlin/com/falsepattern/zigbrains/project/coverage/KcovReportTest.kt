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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/**
 * Fixtures are real kcov 43 reports: `zig test <file> --test-cmd kcov --test-cmd --include-path=<dir>
 * --test-cmd <out> --test-cmd-bin` (zig 0.16, macOS), with the scratch dir replaced by `/project`.
 * cobertura.xml is a single-file binary (absolute filename), cobertura-two-files.xml a root.zig that
 * imports util.zig (filenames relative to `<source>`).
 */
class KcovReportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fixture = javaClass.getResourceAsStream("/kcov/cobertura.xml")!!.readBytes()
    private val twoFiles = javaClass.getResourceAsStream("/kcov/cobertura-two-files.xml")!!.readBytes()
    private val expected = mapOf("/project/cov.zig" to mapOf(3 to 2, 4 to 1, 5 to 1, 7 to 1, 10 to 1, 11 to 1))

    @Test
    fun parsesRealReport() {
        assertEquals(expected, KcovReport.parse(fixture.inputStream()))
    }

    @Test
    fun resolvesRelativeFilenamesAgainstSource() {
        val report = KcovReport.parse(twoFiles.inputStream())
        assertEquals(setOf("/project/src/root.zig", "/project/src/util.zig"), report.keys)
        // neverCalled (7-11) is only referenced, never run: present with 0 hits.
        assertEquals(mapOf(3 to 2, 4 to 1, 7 to 0, 8 to 0, 9 to 0, 11 to 0, 14 to 2, 15 to 1, 16 to 1, 18 to 1, 21 to 1, 22 to 2), report["/project/src/util.zig"])
        assertEquals(mapOf(4 to 1, 5 to 2, 6 to 2, 9 to 1, 10 to 1), report["/project/src/root.zig"])
    }

    @Test
    fun rootSourceRestoresLeadingSlashDroppedWithoutIncludePath() {
        val xml = """<coverage><sources><source>/</source></sources><packages><package><classes><class filename="tmp/a.zig"><lines><line number="2" hits="0"/></lines></class></classes></package></packages></coverage>"""
        assertEquals(mapOf("/tmp/a.zig" to mapOf(2 to 0)), KcovReport.parse(xml.byteInputStream()))
    }

    @Test
    fun sumsHitsOfAFileCompiledIntoTwoBinaries() {
        val out = tmp.newFolder().toPath()
        out.resolve("test.1111").createDirectories().resolve("cobertura.xml").writeBytes(twoFiles)
        // The single-file fixture stands in for a util-only binary: rename its file to util.zig.
        val utilOnly = String(fixture).replace("/project/cov.zig", "/project/src/util.zig").toByteArray()
        out.resolve("test.2222").createDirectories().resolve("cobertura.xml").writeBytes(utilOnly)
        val util = KcovReport.load(out).getValue("/project/src/util.zig")
        // Lines seen by both binaries add up (a line hit in either counts as covered); lines only one binary
        // compiled keep their own count. The two fixtures share lines 3, 4, 7 and 11.
        assertEquals(4, util[3])
        assertEquals(1, util[5])
        assertEquals(0 + 1, util[7])
        assertEquals(0, util[8])
        assertEquals(12 + 2, util.size)
    }

    @Test
    fun sumsPerBinaryReportsAndSkipsTheSymlinkAlias() {
        val out = tmp.newFolder().toPath()
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
        val out = tmp.newFolder().toPath()
        out.resolve("test.1111").createDirectories().resolve("cobertura.xml").writeBytes(fixture)
        out.resolve("kcov-merged").createDirectories().resolve("cobertura.xml").writeBytes(fixture)
        assertEquals(expected, KcovReport.load(out))
    }

    @Test
    fun skipsClassWithoutLinesAndMethodLines() {
        val xml = """<coverage><classes>
            <class filename="/p/empty.zig"><lines/></class>
            <class filename="/p/a.zig"><methods><method><lines><line number="9" hits="5"/></lines></method></methods>
                <lines><line number="2" hits="1"/></lines></class>
            </classes></coverage>"""
        assertEquals(mapOf("/p/a.zig" to mapOf(2 to 1)), KcovReport.parse(xml.byteInputStream()))
    }

    @Test
    fun recognisesReports() {
        val report = tmp.newFile("cobertura.xml").toPath().also { it.writeBytes(fixture) }
        val other = tmp.newFile("other.xml").toPath().also { it.writeBytes("<project/>".toByteArray()) }
        assertTrue(KcovReport.isReport(report))
        assertTrue(KcovReport.isReport(tmp.root.toPath()))
        assertFalse(KcovReport.isReport(other))
    }
}
