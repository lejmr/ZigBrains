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

import com.falsepattern.zigbrains.ZigPlatformTestCase

/** [ZigLineMarkerTestFile]: the "all tests in this file" actions ride on the first top-level named test's gutter icon. */
class ZigLineMarkerTestFileTest : ZigPlatformTestCase() {
    private fun fileActionTexts() = gutterActionTextsAtCaret().filter { "all tests in" in it }

    fun testFirstNamedTestCarriesPerTestAndWholeFileActions() {
        configure("x.zig", "te<caret>st \"a\" {}\ntest \"b\" {}\n")
        assertContainsElements(gutterActionTextsAtCaret(), "Run context configuration", "Debug context configuration")
        assertEquals(listOf("Run 'all tests in x.zig'", "Debug 'all tests in x.zig'"), fileActionTexts())
    }

    fun testLaterTestsOnlyGetPerTestActions() {
        configure("x.zig", "test \"a\" {}\nte<caret>st \"b\" {}\n")
        assertContainsElements(gutterActionTextsAtCaret(), "Run context configuration", "Debug context configuration")
        assertEmpty(fileActionTexts())
    }

    fun testAnonymousFirstTestGetsNoExtraActions() {
        // Its own per-test configuration already is "all tests in x.zig"
        configure("x.zig", "te<caret>st {}\ntest \"b\" {}\n")
        assertContainsElements(gutterActionTextsAtCaret(), "Run context configuration", "Debug context configuration")
        assertEmpty(fileActionTexts())
    }

    fun testNestedTestsDoNotCountAsTheFirstTest() {
        configure("x.zig", "const S = struct {\n    test \"inner\" {}\n};\nte<caret>st \"top\" {}\n")
        assertEquals(listOf("Run 'all tests in x.zig'", "Debug 'all tests in x.zig'"), fileActionTexts())
    }
}
