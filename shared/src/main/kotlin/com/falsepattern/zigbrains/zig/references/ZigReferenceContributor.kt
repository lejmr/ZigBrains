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

import com.falsepattern.zigbrains.zig.ZigElementFactory
import com.falsepattern.zigbrains.zig.psi.ZigPrimaryTypeExpr
import com.falsepattern.zigbrains.zig.psi.ZigSuffixOp
import com.falsepattern.zigbrains.zig.psi.ZigTypes
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.util.elementType
import com.intellij.util.ProcessingContext

/**
 * Registers references on the two PSI shapes an identifier use can take, so the platform's Go to Declaration,
 * Find Usages and Rename work on Zig without ZLS. See [ZigDeclarationResolver] for what resolves and what does not.
 */
class ZigReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(psiElement(ZigPrimaryTypeExpr::class.java), provider { element ->
            // only a bare `foo`; `.foo`, `error.Foo`, `@foo(...)` etc. also carry an IDENTIFIER child
            val id = element.firstChild.takeIf { it.elementType == ZigTypes.IDENTIFIER && it.nextSibling == null }
            id?.let { ZigIdentifierReference(element, it) { ZigDeclarationResolver.resolveName(element, it.text) } }
        })
        registrar.registerReferenceProvider(psiElement(ZigSuffixOp::class.java), provider { element ->
            val op = element as ZigSuffixOp
            val id = op.identifier.takeIf { op.dot != null }
            id?.let { ZigIdentifierReference(op, it) { ZigDeclarationResolver.resolveField(op, it.text) } }
        })
    }

    private fun provider(reference: (PsiElement) -> PsiReference?) = object : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> =
            reference(element)?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
    }
}

/**
 * A reference from [identifier] (an `IDENTIFIER` leaf inside [element]) to the declaration [resolver] finds.
 * Soft: resolution has no type information, so an unresolved name is not an error.
 */
private class ZigIdentifierReference(
    element: PsiElement,
    identifier: PsiElement,
    private val resolver: () -> PsiElement?,
) : PsiReferenceBase<PsiElement>(element, TextRange.from(identifier.startOffsetInParent, identifier.textLength), true) {
    override fun resolve(): PsiElement? =
        ResolveCache.getInstance(element.project).resolveWithCaching(this, { ref, _ -> ref.resolver() }, false, false)

    // No ElementManipulator for these hosts: swap the identifier leaf directly, like ZigNamedDeclMixin.setName.
    override fun handleElementRename(newElementName: String): PsiElement {
        element.findElementAt(rangeInElement.startOffset)
            ?.replace(ZigElementFactory.createIdentifier(element.project, newElementName))
        return element
    }
}
