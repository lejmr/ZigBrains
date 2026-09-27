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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

/** Covers [ZigTestLocator.parse], the pure inverse of [ZigTestLocator.url] (no PSI involved). */
class ZigTestLocatorParseTest {
    private val file = Path.of("/repo/src/root.zig")

    @Test
    fun parsesAWholeUrl() {
        assertEquals(file to "root.test.tokens", ZigTestLocator.parse(ZigTestLocator.url(file.toString(), "root.test.tokens")))
    }

    @Test
    fun parsesThePathWithoutTheProtocolPrefix() {
        assertEquals(file to "root.test.tokens", ZigTestLocator.parse("$file::root.test.tokens"))
    }

    @Test
    fun aSeparatorInsideTheTestNameStaysInTheName() {
        assertEquals(file to "root.test.a::b", ZigTestLocator.parse("zigtest://$file::root.test.a::b"))
    }

    @Test
    fun noSeparatorMeansNoLocation() {
        assertNull(ZigTestLocator.parse("zigtest://$file"))
    }
}
