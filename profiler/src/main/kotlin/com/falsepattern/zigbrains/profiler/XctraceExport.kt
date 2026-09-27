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

/** Reads an `xctrace export` of the `time-profile` table into a [CpuProfile]. */
object XctraceExport {
    const val TIME_PROFILE_XPATH = """/trace-toc/run[@number="1"]/data/table[@schema="time-profile"]"""

    /**
     * Every `<row>` is one sample; its `<backtrace>` lists frames leaf first. xctrace writes each
     * element once with `id="N"` and repeats it as an empty `<tag ref="N"/>`, so refs are resolved
     * against an id index before reading attributes.
     */
    fun parse(xml: InputStream): CpuProfile {
        // DOM keeps the whole export in memory, fine for minutes of samples; StAX if traces get huge
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

        val rows = doc.getElementsByTagName("row")
        val samples = (0 until rows.length).asSequence().mapNotNull { i ->
            val backtrace = (rows.item(i) as Element).children("backtrace").firstOrNull()?.deref() ?: return@mapNotNull null
            Sample(backtrace.children("frame").map { raw ->
                val frame = raw.deref()
                val name = frame.getAttribute("name").ifEmpty { frame.getAttribute("addr") }
                val src = frame.children("source").firstOrNull()?.deref()
                Frame(name, src?.children("path")?.firstOrNull()?.deref()?.textContent, src?.getAttribute("line")?.toIntOrNull())
            })
        }
        return CpuProfile.of(samples)
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
    }
}
