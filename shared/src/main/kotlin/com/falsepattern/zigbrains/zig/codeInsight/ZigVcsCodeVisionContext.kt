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

package com.falsepattern.zigbrains.zig.codeInsight

import com.falsepattern.zigbrains.zig.parser.ZigTokenSets
import com.falsepattern.zigbrains.zig.psi.ZigTypes
import com.intellij.codeInsight.hints.VcsCodeVisionLanguageContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import java.awt.event.MouseEvent

/**
 * Shows the VCS "code author" inlay (clicking it opens Annotate with Git Blame)
 * above functions, tests and container type declarations.
 */
class ZigVcsCodeVisionContext: VcsCodeVisionLanguageContext {
    override fun isAccepted(element: PsiElement): Boolean {
        if (element.elementType != ZigTypes.CONTAINER_DECLARATION)
            return false
        return declarationBody(element) != null
    }

    override fun handleClick(mouseEvent: MouseEvent, editor: Editor, element: PsiElement) {
        // The platform already opens the annotation on click
    }

    override fun computeEffectiveRange(element: PsiElement): TextRange {
        // Skip the doc comment and `pub`, so a rewritten declaration is shown as new
        val body = declarationBody(element) ?: return element.textRange
        return TextRange(body.textRange.startOffset, element.textRange.endOffset)
    }
}

private val containerStartTokens = TokenSet.create(
    ZigTypes.KEYWORD_STRUCT,
    ZigTypes.KEYWORD_ENUM,
    ZigTypes.KEYWORD_UNION,
    ZigTypes.KEYWORD_OPAQUE,
    ZigTypes.KEYWORD_EXTERN,
    ZigTypes.KEYWORD_PACKED,
)

/**
 * Returns the `test`/`fn`/`const X = struct {...}` child of a container declaration,
 * or null for declarations that should not get an author lens
 * (function prototypes without a body, plain constants, imports, comptime blocks).
 */
private fun declarationBody(containerDeclaration: PsiElement): PsiElement? {
    for (child in containerDeclaration.children) {
        when (child.elementType) {
            ZigTypes.TEST_DECL -> return child
            ZigTypes.DECL -> return child.takeIf { isFunctionDefinition(it) || isContainerTypeDeclaration(it) }
        }
    }
    return null
}

private fun isFunctionDefinition(decl: PsiElement): Boolean =
    decl.children.any { it.elementType == ZigTypes.FN_PROTO } &&
    decl.children.any { it.elementType == ZigTypes.BLOCK }

private fun isContainerTypeDeclaration(decl: PsiElement): Boolean {
    val varDecl = decl.children.firstOrNull { it.elementType == ZigTypes.GLOBAL_VAR_DECL } ?: return false
    val equal = varDecl.node.findChildByType(ZigTypes.EQUAL)?.psi ?: return false
    var next = PsiTreeUtil.nextLeaf(equal)
    while (next != null && (next.elementType == TokenType.WHITE_SPACE || next.elementType in ZigTokenSets.COMMENTS))
        next = PsiTreeUtil.nextLeaf(next)
    return next.elementType in containerStartTokens
}
