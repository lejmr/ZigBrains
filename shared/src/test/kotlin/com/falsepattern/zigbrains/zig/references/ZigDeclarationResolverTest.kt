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

package com.falsepattern.zigbrains.zig.references

import com.falsepattern.zigbrains.ZigPlatformTestCase
import com.falsepattern.zigbrains.zig.psi.ZigFnProto
import com.falsepattern.zigbrains.zig.psi.ZigParamDecl
import com.falsepattern.zigbrains.zig.psi.ZigVarDeclProto
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * Resolution through the real path a Cmd+click takes (`PsiFile.findReferenceAt(caret).resolve()`, which
 * only sees references the PSI hands out itself), and Find Usages through the real `ReferencesSearch`,
 * both without ZLS.
 */
class ZigDeclarationResolverTest : ZigPlatformTestCase() {
    private fun resolveAtCaret(): PsiElement? = myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve()

    private fun nameOf(e: PsiElement?) = (e as? PsiNamedElement)?.name

    fun testResolvesAFunctionParameter() {
        configure("a.zig", "pub fn add(a: i64, b: i64) i64 {\n    return <caret>a + b;\n}")
        val target = resolveAtCaret()
        assertTrue(target is ZigParamDecl)
        assertEquals("a", nameOf(target))
    }

    fun testResolvesALocalVariableDeclaredEarlierInTheSameBlock() {
        configure("a.zig", "pub fn f() i64 {\n    const x: i64 = 5;\n    return <caret>x;\n}")
        val target = resolveAtCaret()
        assertTrue(target is ZigVarDeclProto)
        assertEquals("x", nameOf(target))
    }

    fun testDoesNotResolveALocalVariableDeclaredLaterInTheBlock() {
        configure("a.zig", "pub fn f() i64 {\n    const y = <caret>x;\n    const x: i64 = 5;\n    return y;\n}")
        assertNull(resolveAtCaret())
    }

    fun testResolvesAForwardReferenceToASiblingFunction() {
        configure("a.zig", "pub fn caller() i64 {\n    return <caret>callee();\n}\nfn callee() i64 {\n    return 1;\n}")
        val target = resolveAtCaret()
        assertTrue(target is ZigFnProto)
        assertEquals("callee", nameOf(target))
    }

    fun testFieldAccessOnAnImportedConstResolvesIntoTheImportedFile() {
        myFixture.addFileToProject("calc.zig", "pub fn add(a: i64, b: i64) i64 { return a + b; }")
        configure("main.zig", "const calc = @import(\"calc.zig\");\npub fn main() void {\n    _ = calc.<caret>add(2, 3);\n}")
        val target = resolveAtCaret()
        assertEquals("add", nameOf(target))
        assertEquals("calc.zig", target?.containingFile?.name)
    }

    fun testAnUnrelatedIdentifierResolvesToNothingRatherThanThrowing() {
        configure("a.zig", "pub fn f() void {\n    <caret>totallyUnknown();\n}")
        assertNull(resolveAtCaret())
    }

    fun testFindUsagesOfAFunctionFindsSameFileAndImportingFileCalls() {
        myFixture.addFileToProject(
            "main.zig",
            "const calc = @import(\"calc.zig\");\npub fn main() void {\n    _ = calc.add(2, 3);\n}"
        )
        val file = configure("calc.zig", "pub fn add(a: i64, b: i64) i64 { return a + b; }\ntest \"add\" { _ = add(1, 1); }")
        val fn = PsiTreeUtil.findChildOfType(file, ZigFnProto::class.java)!!
        val usageFiles = ReferencesSearch.search(fn, GlobalSearchScope.projectScope(project)).findAll()
            .map { it.element.containingFile.name }
            .sorted()
        assertEquals(listOf("calc.zig", "main.zig"), usageFiles)
    }
}
