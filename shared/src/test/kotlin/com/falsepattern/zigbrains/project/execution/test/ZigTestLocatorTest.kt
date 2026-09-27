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
import com.falsepattern.zigbrains.zig.psi.ZigTestDecl
import com.falsepattern.zigbrains.zig.psi.ZigTypes
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.elementType

/** [ZigTestLocator] resolving the `zigtest://<file>::<fullName>` urls the test tree carries. */
class ZigTestLocatorTest : ZigPlatformTestCase() {
    private lateinit var root: PsiFile

    override fun setUp() {
        super.setUp()
        root = myFixture.addFileToProject("root.zig", """
            const std = @import("std");
            const helper = @import("utils/helper.zig");
            fn foo() void {}
            test "name" {}
            test "x" {}
            test foo {}
            test {}
            test {}
            const S = struct {
                test "inside" {}
                test {}
            };
            test {
                _ = S;
                _ = helper;
            }
        """.trimIndent())
        myFixture.addFileToProject("utils/helper.zig", "test \"x\" {}\ntest {}\ntest \"y\" {}\n")
    }

    private fun locate(fullName: String, file: PsiFile = root): PsiElement? {
        val path = file.virtualFile.toNioPath().toString()
        val locations = ZigTestLocator.getLocation(ZigTestLocator.PROTOCOL, "$path::$fullName", project, GlobalSearchScope.allScope(project))
        return locations.singleOrNull()?.psiElement
    }

    /** The resolved element is the `test` keyword leaf; returns the declaration's own text for assertions. */
    private fun declText(element: PsiElement?): String {
        assertNotNull(element)
        assertEquals(ZigTypes.KEYWORD_TEST, element!!.elementType)
        return (element.parent as ZigTestDecl).text
    }

    fun testNamedTest() {
        assertEquals("test \"name\" {}", declText(locate("root.test.name")))
    }

    fun testDeclTest() {
        assertEquals("test foo {}", declText(locate("root.decltest.foo")))
    }

    fun testAnonymousTestsAreIndexedPerContainer() {
        assertEquals("test {}", declText(locate("root.test_0")))
        assertEquals("test {}", declText(locate("root.test_1")))
        assertTrue(declText(locate("root.test_2")).startsWith("test {\n"))
        assertNull(locate("root.test_3"))
        // The struct's own anonymous test is its index 0, not the file's
        assertEquals("test {}", declText(locate("root.S.test_0")))
        assertNull(locate("root.S.test_1"))
    }

    fun testNestedContainer() {
        assertEquals("test \"inside\" {}", declText(locate("root.S.test.inside")))
    }

    fun testImportedModuleResolvesToTheSiblingFile() {
        // root.zig has its own test "x": the module path must win over the running file
        val element = locate("utils.helper.test.x")
        assertEquals("test \"x\" {}", declText(element))
        assertEquals("helper.zig", element!!.containingFile.name)
        assertEquals("root.zig", locate("root.test.x")!!.containingFile.name)
        assertEquals("test \"y\" {}", declText(locate("utils.helper.test.y")))
        val anonymous = locate("utils.helper.test_0")
        assertEquals("test {}", declText(anonymous))
        assertEquals("helper.zig", anonymous!!.containingFile.name)
    }

    fun testUnknownNamesResolveToNothing() {
        assertNull(locate("root.test.missing"))
        assertNull(locate("nowhere.test.name"))
        assertNull(locate("root.S.test.name"))
    }

    fun testOtherProtocolsAreIgnored() {
        val path = root.virtualFile.toNioPath().toString()
        assertEmpty(ZigTestLocator.getLocation("python", "$path::root.test.name", project, GlobalSearchScope.allScope(project)))
    }

    /** File and module suites carry a plain `file://<path>` location (see [ZigTestEventsConverter]); the
     *  platform strips the protocol before calling the locator, so this is what actually reaches it, and
     *  it must resolve to a navigatable location (F4/double-click "Jump to Source" on the suite). */
    fun testFileProtocolDelegatesToFileUrlProvider() {
        val path = root.virtualFile.toNioPath().toString()
        val location = ZigTestLocator.getLocation("file", path, project, GlobalSearchScope.allScope(project)).singleOrNull()
        assertEquals(root.virtualFile, location?.psiElement?.containingFile?.virtualFile)
    }

    /** A package suite has no file of its own, so [ZigTestEventsConverter.packageStarted] points it at the
     *  package's `build.zig` instead of leaving it without a location. */
    fun testPackageSuiteLocationIsBuildZig() {
        val buildZig = myFixture.addFileToProject("build.zig", "")
        val path = buildZig.virtualFile.toNioPath().toString()
        val location = ZigTestLocator.getLocation("file", path, project, GlobalSearchScope.allScope(project)).singleOrNull()
        assertEquals(buildZig.virtualFile, location?.psiElement?.containingFile?.virtualFile)
    }
}
