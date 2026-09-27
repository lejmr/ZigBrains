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

import com.falsepattern.zigbrains.zig.ZigLanguage
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import javax.swing.Icon

/**
 * Breadcrumbs (and, through them, sticky lines) for functions, tests,
 * comptime blocks and struct/enum/union/opaque declarations.
 */
class ZigBreadcrumbsProvider: BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(ZigLanguage)

    override fun acceptElement(element: PsiElement): Boolean =
        declarationBody(element)?.let(::declarationKind) != null

    override fun getElementInfo(element: PsiElement): String {
        val body = declarationBody(element) ?: return ""
        val name = declarationName(body)
        return when (declarationKind(body)) {
            ZigDeclarationKind.FUNCTION -> "${name ?: "fn"}()"
            ZigDeclarationKind.TEST -> if (name != null) "test $name" else "test"
            ZigDeclarationKind.CONTAINER_TYPE -> name ?: "struct"
            ZigDeclarationKind.COMPTIME -> "comptime"
            null -> ""
        }
    }

    override fun getElementIcon(element: PsiElement): Icon? {
        val body = declarationBody(element) ?: return null
        return when (declarationKind(body)) {
            ZigDeclarationKind.FUNCTION -> AllIcons.Nodes.Function
            ZigDeclarationKind.TEST -> AllIcons.RunConfigurations.TestState.Run
            ZigDeclarationKind.CONTAINER_TYPE -> AllIcons.Nodes.Class
            ZigDeclarationKind.COMPTIME, null -> null
        }
    }
}
