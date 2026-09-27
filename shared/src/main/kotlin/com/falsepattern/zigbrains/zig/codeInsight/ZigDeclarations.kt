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
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType

/**
 * Helpers for recognizing the "interesting" top-level/member declarations
 * (functions, tests, container types) in the generated Zig PSI.
 */
internal enum class ZigDeclarationKind { FUNCTION, TEST, CONTAINER_TYPE, COMPTIME }

private val containerStartTokens = TokenSet.create(
    ZigTypes.KEYWORD_STRUCT,
    ZigTypes.KEYWORD_ENUM,
    ZigTypes.KEYWORD_UNION,
    ZigTypes.KEYWORD_OPAQUE,
    ZigTypes.KEYWORD_EXTERN,
    ZigTypes.KEYWORD_PACKED,
)

/**
 * Returns the `test`/`fn`/`const X = struct {...}`/`comptime` child of a container declaration,
 * or null for anything else (function prototypes without a body, plain constants, imports).
 */
internal fun declarationBody(containerDeclaration: PsiElement): PsiElement? {
    if (containerDeclaration.elementType != ZigTypes.CONTAINER_DECLARATION)
        return null
    for (child in containerDeclaration.children) {
        when (child.elementType) {
            ZigTypes.TEST_DECL, ZigTypes.COMPTIME_DECL -> return child
            ZigTypes.DECL -> return child.takeIf { isFunctionDefinition(it) || isContainerTypeDeclaration(it) }
        }
    }
    return null
}

internal fun declarationKind(body: PsiElement): ZigDeclarationKind? = when (body.elementType) {
    ZigTypes.TEST_DECL -> ZigDeclarationKind.TEST
    ZigTypes.COMPTIME_DECL -> ZigDeclarationKind.COMPTIME
    ZigTypes.DECL -> when {
        isFunctionDefinition(body) -> ZigDeclarationKind.FUNCTION
        isContainerTypeDeclaration(body) -> ZigDeclarationKind.CONTAINER_TYPE
        else -> null
    }
    else -> null
}

/** Name of a function, test or container type declaration body, if it has one. */
internal fun declarationName(body: PsiElement): String? = when (body.elementType) {
    ZigTypes.TEST_DECL ->
        (body.node.findChildByType(ZigTypes.STRING_LITERAL_SINGLE) ?: body.node.findChildByType(ZigTypes.IDENTIFIER))?.text
    ZigTypes.DECL -> {
        val proto = body.children.firstOrNull { it.elementType == ZigTypes.FN_PROTO }
            ?: body.children.firstOrNull { it.elementType == ZigTypes.GLOBAL_VAR_DECL }
                ?.children?.firstOrNull { it.elementType == ZigTypes.VAR_DECL_PROTO }
        proto?.node?.findChildByType(ZigTypes.IDENTIFIER)?.text
    }
    else -> null
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
