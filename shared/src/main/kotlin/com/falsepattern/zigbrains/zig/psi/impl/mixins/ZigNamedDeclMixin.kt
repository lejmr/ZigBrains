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

package com.falsepattern.zigbrains.zig.psi.impl.mixins

import com.falsepattern.zigbrains.zig.ZigElementFactory
import com.falsepattern.zigbrains.zig.psi.ZigBlock
import com.falsepattern.zigbrains.zig.psi.ZigDecl
import com.falsepattern.zigbrains.zig.psi.ZigParamDecl
import com.falsepattern.zigbrains.zig.psi.ZigTypes
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * Shared by [com.falsepattern.zigbrains.zig.psi.ZigParamDecl], [com.falsepattern.zigbrains.zig.psi.ZigVarDeclProto]
 * and [com.falsepattern.zigbrains.zig.psi.ZigFnProto] (see Zig.bnf's `mixin(...)`/`implements(...)`): all three
 * have a plain `IDENTIFIER` as a direct child, so one generic lookup covers them instead of three copies of the
 * same three methods. This is what lets the platform's own "Go to Declaration or Usages" fall back to Find
 * Usages when the caret is already on the declaration - previously "Cannot find declaration to go to", since
 * nothing marked these as named declarations at all.
 */
abstract class ZigNamedDeclMixin(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(ZigTypes.IDENTIFIER)?.psi

    override fun getName(): String? = nameIdentifier?.text

    // TargetElementUtilBase.getNamedElement only treats the caret as "on this declaration" when the named
    // element's text offset equals the caret leaf's start, so it has to point at the name, not at `fn`/`const`.
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun setName(name: String): PsiElement {
        val current = nameIdentifier ?: return this
        current.replace(ZigElementFactory.createIdentifier(project, name))
        return this
    }

    // A parameter is only visible in its function, anything declared in a block only inside that block; keeps
    // Find Usages/Rename of locals off the project-wide word index.
    override fun getUseScope(): SearchScope {
        val scope = if (this is ZigParamDecl) PsiTreeUtil.getParentOfType(this, ZigDecl::class.java)
        else PsiTreeUtil.getParentOfType(this, ZigBlock::class.java)
        return scope?.let(::LocalSearchScope) ?: super.getUseScope()
    }
}
