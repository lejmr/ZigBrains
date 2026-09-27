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
import org.junit.Test

/** Covers only [ZigTestEventsConverter]'s pure companion helpers (no IntelliJ test fixtures needed): the
 *  module-suite nesting decision itself (does a test's module path match the running file?) is exercised
 *  through these, not through the full SMTRunner event stream. */
class ZigTestEventsConverterTest {
    @Test
    fun stemOfStripsTheZigExtension() {
        assertEquals("root", ZigTestEventsConverter.stemOf("/home/user/project/root.zig"))
    }

    @Test
    fun stemOfKeepsDotsThatAreActuallyPartOfTheFileName() {
        // "a.b.zig" is the file's whole stem "a.b", not just "b" -- a module path of "a.b" must compare
        // equal to it (no nesting), while a module path of just "b" must not.
        assertEquals("a.b", ZigTestEventsConverter.stemOf("/home/user/project/a.b.zig"))
    }

    @Test
    fun moduleFileOfResolvesADottedModulePathToASiblingFile() {
        assertEquals(
            "/home/user/project/Lexer.zig",
            ZigTestEventsConverter.moduleFileOf("/home/user/project/root.zig", "Lexer"),
        )
        assertEquals(
            "/home/user/project/utils/unqoute.zig",
            ZigTestEventsConverter.moduleFileOf("/home/user/project/root.zig", "utils.unqoute"),
        )
    }
}
