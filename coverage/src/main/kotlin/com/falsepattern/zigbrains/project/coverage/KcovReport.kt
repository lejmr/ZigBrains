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

import java.io.InputStream
import java.nio.file.Path
import java.util.SortedMap
import java.util.TreeMap
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries

/** Line hits per absolute source path, read from kcov's cobertura.xml reports. */
object KcovReport {
    /**
     * [path] is either one cobertura.xml or a kcov output dir. For a dir, kcov-merged/cobertura.xml wins when
     * kcov wrote one; otherwise every per-binary `<dir>/<binary>.<hash>/cobertura.xml` is summed (one `zig test`
     * binary per test file). Symlinks (kcov's `<binary>` alias of the last run) are skipped to avoid counting twice.
     */
    fun load(path: Path): SortedMap<String, SortedMap<Int, Int>> {
        val result = TreeMap<String, SortedMap<Int, Int>>()
        val merged = path.resolve("kcov-merged").resolve("cobertura.xml")
        val reports = when {
            !path.isDirectory() -> listOf(path)
            merged.isRegularFile() -> listOf(merged)
            else -> path.listDirectoryEntries().filter { !it.isSymbolicLink() && it.isDirectory() }
                .map { it.resolve("cobertura.xml") }.filter { it.exists() }
        }
        for (report in reports) {
            report.inputStream().use { parse(it, result) }
        }
        return result
    }

    /** True for a kcov output dir or an XML file whose root element is `<coverage>`. */
    fun isReport(path: Path): Boolean {
        if (path.isDirectory()) return true
        return try {
            path.inputStream().use { input ->
                val reader = xmlFactory().createXMLStreamReader(input)
                try {
                    while (reader.hasNext()) {
                        if (reader.next() == XMLStreamConstants.START_ELEMENT) return reader.localName == "coverage"
                    }
                    false
                } finally {
                    reader.close()
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Adds the `<class filename>`/`<line number hits>` pairs of one report into [into], summing hits.
     *
     * kcov puts the common directory of all files into `<source>` and writes `filename` relative to it as soon
     * as a binary has more than one file (`src/` + `util.zig`, or `/` + `Users/...` without `--include-path`);
     * with a single file `filename` stays absolute. Relative names are resolved against `<source>`.
     */
    fun parse(input: InputStream, into: SortedMap<String, SortedMap<Int, Int>> = TreeMap()): SortedMap<String, SortedMap<Int, Int>> {
        val reader = xmlFactory().createXMLStreamReader(input)
        try {
            var source = "/"
            var file: String? = null
            // Cobertura repeats a method's lines under <methods>; only the class-level <lines> count.
            var methodsDepth = 0
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.END_ELEMENT -> if (reader.localName == "methods") methodsDepth--
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "source" -> source = reader.elementText.trim().trimEnd('/') + "/"
                        "methods" -> methodsDepth++
                        "class" -> file = reader.getAttributeValue(null, "filename")?.let { if (it.startsWith("/")) it else source + it }
                        "line" -> {
                            val number = reader.getAttributeValue(null, "number")?.toIntOrNull()
                            val hits = reader.getAttributeValue(null, "hits")?.toIntOrNull()
                            // The map is created on the first line, so a class without lines leaves no empty entry.
                            if (methodsDepth == 0 && file != null && number != null && hits != null) {
                                into.getOrPut(file) { TreeMap() }.merge(number, hits, Int::plus)
                            }
                        }
                    }
                }
            }
        } finally {
            reader.close()
        }
        return into
    }

    // The report declares a remote DTD; never fetch it.
    private fun xmlFactory() = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }
}
