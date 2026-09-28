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

package com.falsepattern.zigbrains.zig.psi

import com.falsepattern.zigbrains.ZigPlatformTestCase
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameUtil

/**
 * [com.falsepattern.zigbrains.zig.psi.impl.mixins.ZigNamedDeclMixin], applied via Zig.bnf's mixin() to
 * ZigFnProto/ZigParamDecl/ZigVarDeclProto: the platform only offers "Find Usages" as a fallback for a
 * caret already on a declaration (Cmd+click's "Cannot find declaration to go to" case) when the element is
 * a real PsiNameIdentifierOwner, not just something a PsiReference happens to resolve to.
 */
class ZigNamedDeclMixinTest : ZigPlatformTestCase() {
    fun testFunctionNameIsANamedDeclaration() {
        val file = configure("a.zig", "pub fn add(a: i64, b: i64) i64 { return a + b; }")
        val fnProto = PsiTreeUtil.findChildOfType(file, ZigFnProto::class.java)!!
        val owner = fnProto as? PsiNameIdentifierOwner
        assertNotNull("ZigFnProto should implement PsiNameIdentifierOwner", owner)
        assertEquals("add", owner!!.name)
        assertEquals("add", owner.nameIdentifier?.text)
    }

    fun testParameterIsANamedDeclaration() {
        val file = configure("a.zig", "pub fn add(a: i64, b: i64) i64 { return a + b; }")
        val param = PsiTreeUtil.findChildOfType(file, ZigParamDecl::class.java)!!
        val owner = param as? PsiNameIdentifierOwner
        assertNotNull("ZigParamDecl should implement PsiNameIdentifierOwner", owner)
        assertEquals("a", owner!!.name)
    }

    fun testLocalVariableIsANamedDeclaration() {
        val file = configure("a.zig", "pub fn f() i64 { const x: i64 = 5; return x; }")
        val varDecl = PsiTreeUtil.findChildOfType(file, ZigVarDeclProto::class.java)!!
        val owner = varDecl as? PsiNameIdentifierOwner
        assertNotNull("ZigVarDeclProto should implement PsiNameIdentifierOwner", owner)
        assertEquals("x", owner!!.name)
    }

    // What Cmd+click actually runs: GTD (jump to declaration) or SU (show usages).
    private fun cmdClickOutcome() =
        GotoDeclarationOrUsageHandler2.testGTDUOutcome(myFixture.editor, myFixture.file, myFixture.caretOffset)

    fun testCmdClickOnAFunctionNameShowsUsages() {
        configure("a.zig", "pub fn <caret>add(a: i64, b: i64) i64 { return a + b; }\ntest \"add\" { _ = add(2, 3); }")
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.SU, cmdClickOutcome())
    }

    fun testCmdClickOnAConstNameShowsUsages() {
        configure("a.zig", "const <caret>answer: i64 = 42;\nfn f() i64 { return answer; }")
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.SU, cmdClickOutcome())
    }

    fun testCmdClickOnAParameterNameShowsUsages() {
        configure("a.zig", "pub fn add(<caret>a: i64, b: i64) i64 { return a + b; }")
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.SU, cmdClickOutcome())
    }

    fun testCmdClickOnAUsageJumpsToTheDeclaration() {
        configure("a.zig", "pub fn add(a: i64, b: i64) i64 { return a + b; }\ntest \"add\" { _ = <caret>add(2, 3); }")
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, cmdClickOutcome())
    }

    fun testRenamingAParameterReplacesItsIdentifierText() {
        val file = configure("a.zig", "pub fn add(a: i64, b: i64) i64 { return a + b; }")
        val param = PsiTreeUtil.findChildOfType(file, ZigParamDecl::class.java)!! as PsiNameIdentifierOwner
        WriteCommandAction.runWriteCommandAction(project) {
            RenameUtil.doRenameGenericNamedElement(param, "renamed", arrayOf(), null)
        }
        assertEquals("renamed", param.name)
        assertTrue(file.text.contains("pub fn add(renamed: i64"))
    }
}
