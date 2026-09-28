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

import com.falsepattern.zigbrains.zig.psi.ZigPrimaryTypeExpr
import com.falsepattern.zigbrains.zig.psi.ZigSuffixOp
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext

/**
 * Registers [ZigNameReference]/[ZigFieldReference] on the two PSI shapes an identifier expression can
 * take, so the platform's generic "Go to Declaration", "Find Usages" and rename all pick Zig identifiers
 * up the same way they would a language with a hand-written resolver -- see [ZigDeclarationResolver] for
 * what actually gets resolved and what does not.
 */
class ZigReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigPrimaryTypeExpr::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val expr = element as ZigPrimaryTypeExpr
                    val id = expr.identifier ?: return PsiReference.EMPTY_ARRAY
                    // only a bare name ("foo"), not @builtin(...), .init, error.X, a nested container, etc.
                    // (PrimaryTypeExpr's other alternatives are all mutually exclusive with a plain identifier)
                    if (expr.builtinidentifier != null || expr.containerDecl != null || expr.errorSetDecl != null ||
                        expr.expr != null || expr.fnCallArguments != null || expr.fnProto != null ||
                        expr.initList != null || expr.stringLiteral != null
                    ) return PsiReference.EMPTY_ARRAY
                    return arrayOf(ZigNameReference(expr, TextRange(id.startOffsetInParent, id.startOffsetInParent + id.textLength)))
                }
            }
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigSuffixOp::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val op = element as ZigSuffixOp
                    if (op.dot == null) return PsiReference.EMPTY_ARRAY
                    val id = op.identifier ?: return PsiReference.EMPTY_ARRAY
                    return arrayOf(ZigFieldReference(op, TextRange(id.startOffsetInParent, id.startOffsetInParent + id.textLength)))
                }
            }
        )
    }
}

private class ZigNameReference(element: ZigPrimaryTypeExpr, range: TextRange) : PsiReferenceBase<ZigPrimaryTypeExpr>(element, range) {
    override fun resolve(): PsiElement? = ZigDeclarationResolver.resolveName(element, element.identifier!!.text)
}

private class ZigFieldReference(element: ZigSuffixOp, range: TextRange) : PsiReferenceBase<ZigSuffixOp>(element, range) {
    override fun resolve(): PsiElement? = ZigDeclarationResolver.resolveField(element, element.identifier!!.text)
}
