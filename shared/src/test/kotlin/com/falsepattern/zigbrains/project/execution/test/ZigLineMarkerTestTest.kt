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

/** [ZigLineMarkerTest]: a run gutter icon on every top-level test, none on tests inside a container. */
class ZigLineMarkerTestTest : ZigPlatformTestCase() {
    fun testTopLevelTestsGetARunGutter() {
        configure("x.zig", "test \"a\" {}\nte<caret>st \"b\" {}\ntest {}\n")
        assertContainsElements(gutterActionTextsAtCaret(), "Run context configuration", "Debug context configuration")
        assertEquals(3, myFixture.findAllGutters().size)
    }

    fun testNestedTestsGetNoGutter() {
        configure("x.zig", "const S = struct {\n    te<caret>st \"inner\" {}\n};\n")
        assertEmpty(myFixture.findGuttersAtCaret())
        assertEmpty(myFixture.findAllGutters())
    }

    fun testOtherDeclarationsGetNoGutter() {
        configure("x.zig", "fn hel<caret>per() void {}\ntest \"a\" {}\n")
        assertEmpty(myFixture.findGuttersAtCaret())
    }
}
