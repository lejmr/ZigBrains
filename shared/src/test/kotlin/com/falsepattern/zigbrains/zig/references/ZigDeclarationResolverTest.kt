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
import com.falsepattern.zigbrains.zig.psi.ZigPrimaryTypeExpr
import com.falsepattern.zigbrains.zig.psi.ZigSuffixOp

/**
 * [ZigDeclarationResolver] driven from real parsed files (not hand-built PSI), the same shapes
 * [ZigReferenceContributor] hands it from a live Cmd+click; verified live in CLion (the caret's
 * PsiReference itself is not exercised here, since [PsiFile.findReferenceAt] resolves stale in this
 * module's light test fixture - a test-harness classloading quirk, not a product bug).
 */
class ZigDeclarationResolverTest : ZigPlatformTestCase() {
    private fun nameExprAtCaret() = elementAtCaret().parent as ZigPrimaryTypeExpr
    private fun fieldOpAtCaret() = elementAtCaret().parent as ZigSuffixOp

    fun testResolvesAFunctionParameter() {
        configure(
            "a.zig",
            """
            pub fn add(a: i64, b: i64) i64 {
                return <caret>a + b;
            }
            """.trimIndent()
        )
        val expr = nameExprAtCaret()
        val target = ZigDeclarationResolver.resolveName(expr, expr.identifier!!.text)
        assertEquals("a", target?.text)
    }

    fun testResolvesALocalVariableDeclaredEarlierInTheSameBlock() {
        configure(
            "a.zig",
            """
            pub fn f() i64 {
                const x: i64 = 5;
                return <caret>x;
            }
            """.trimIndent()
        )
        val expr = nameExprAtCaret()
        val target = ZigDeclarationResolver.resolveName(expr, expr.identifier!!.text)
        assertEquals("x", target?.text)
    }

    fun testDoesNotResolveALocalVariableDeclaredLaterInTheBlock() {
        configure(
            "a.zig",
            """
            pub fn f() i64 {
                const y = <caret>x;
                const x: i64 = 5;
                return y;
            }
            """.trimIndent()
        )
        val expr = nameExprAtCaret()
        assertNull(ZigDeclarationResolver.resolveName(expr, expr.identifier!!.text))
    }

    fun testResolvesAForwardReferenceToASiblingFunction() {
        configure(
            "a.zig",
            """
            pub fn caller() i64 {
                return <caret>callee();
            }
            fn callee() i64 {
                return 1;
            }
            """.trimIndent()
        )
        val expr = nameExprAtCaret()
        val target = ZigDeclarationResolver.resolveName(expr, expr.identifier!!.text)
        assertEquals("callee", target?.text)
    }

    fun testFieldAccessOnAnImportedConstResolvesIntoTheImportedFile() {
        myFixture.addFileToProject("calc.zig", "pub fn add(a: i64, b: i64) i64 { return a + b; }")
        configure(
            "main.zig",
            """
            const calc = @import("calc.zig");
            pub fn main() void {
                _ = calc.<caret>add(2, 3);
            }
            """.trimIndent()
        )
        val op = fieldOpAtCaret()
        val target = ZigDeclarationResolver.resolveField(op, op.identifier!!.text)
        assertEquals("add", target?.text)
        assertEquals("calc.zig", target?.containingFile?.name)
    }

    fun testAnUnrelatedIdentifierResolvesToNothingRatherThanThrowing() {
        configure(
            "a.zig",
            """
            pub fn f() void {
                <caret>totallyUnknown();
            }
            """.trimIndent()
        )
        val expr = nameExprAtCaret()
        assertNull(ZigDeclarationResolver.resolveName(expr, expr.identifier!!.text))
    }
}
