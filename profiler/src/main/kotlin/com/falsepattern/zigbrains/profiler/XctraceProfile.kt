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

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Sample counts of one function; [file]/[line] come from the first frame of it that carried debug info. */
data class FunctionStats(val name: String, val self: Int, val total: Int, val file: String?, val line: Int?)

/** A flat self/total profile built from an `xctrace export` of the `time-profile` table. */
class XctraceProfile(val samples: Int, val functions: List<FunctionStats>) {
    companion object {
        const val TIME_PROFILE_XPATH = """/trace-toc/run[@number="1"]/data/table[@schema="time-profile"]"""

        /**
         * Every `<row>` is one sample; its `<backtrace>` lists frames leaf first. xctrace writes each
         * element once with `id="N"` and repeats it as an empty `<tag ref="N"/>`, so refs are resolved
         * against an id index before reading attributes.
         */
        fun parse(xml: InputStream): XctraceProfile {
            // ponytail: DOM keeps the whole export in memory, fine for minutes of samples; StAX if traces get huge
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val doc = factory.newDocumentBuilder().parse(xml)
            val byId = HashMap<String, Element>()
            val all = doc.getElementsByTagName("*")
            for (i in 0 until all.length) {
                val e = all.item(i) as Element
                val id = e.getAttribute("id")
                if (id.isNotEmpty()) byId[id] = e
            }
            fun Element.deref(): Element = getAttribute("ref").takeIf { it.isNotEmpty() }?.let { byId[it] } ?: this

            val self = HashMap<String, Int>()
            val total = HashMap<String, Int>()
            val source = HashMap<String, Pair<String, Int>>()
            val rows = doc.getElementsByTagName("row")
            for (i in 0 until rows.length) {
                val backtrace = (rows.item(i) as Element).children("backtrace").firstOrNull()?.deref() ?: continue
                val names = backtrace.children("frame").map { raw ->
                    val frame = raw.deref()
                    val name = frame.getAttribute("name").ifEmpty { frame.getAttribute("addr") }
                    frame.children("source").firstOrNull()?.let { src ->
                        val path = src.children("path").firstOrNull()?.deref()?.textContent
                        val line = src.getAttribute("line").toIntOrNull()
                        if (path != null && line != null) source.putIfAbsent(name, path to line)
                    }
                    name
                }
                if (names.isEmpty()) continue
                self.merge(names.first(), 1, Int::plus)
                // a recursive function counts once per sample in its total
                names.toSet().forEach { total.merge(it, 1, Int::plus) }
            }
            val functions = total.map { (name, t) ->
                FunctionStats(name, self[name] ?: 0, t, source[name]?.first, source[name]?.second)
            }.sortedWith(compareByDescending<FunctionStats> { it.self }.thenByDescending { it.total })
            return XctraceProfile(rows.length, functions)
        }

        private fun Element.children(tag: String): List<Element> {
            val nodes = childNodes
            return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
        }
    }
}
