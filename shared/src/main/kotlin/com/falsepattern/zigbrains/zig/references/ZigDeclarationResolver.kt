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

import com.falsepattern.zigbrains.project.buildscan.zigBuildScan
import com.falsepattern.zigbrains.shared.psi.decodeTextContent
import com.falsepattern.zigbrains.zig.psi.ZigBlock
import com.falsepattern.zigbrains.zig.psi.ZigContainerMembers
import com.falsepattern.zigbrains.zig.psi.ZigDecl
import com.falsepattern.zigbrains.zig.psi.ZigFnProto
import com.falsepattern.zigbrains.zig.psi.ZigGlobalVarDecl
import com.falsepattern.zigbrains.zig.psi.ZigPrimaryTypeExpr
import com.falsepattern.zigbrains.zig.psi.ZigStringLiteral
import com.falsepattern.zigbrains.zig.psi.ZigSuffixOp
import com.falsepattern.zigbrains.zig.psi.ZigVarDeclExprStatement
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/**
 * Resolves the everyday 20% of Zig name resolution a full type-checker would otherwise be needed for: a
 * bare identifier to a function parameter, a local `const`/`var`, or a sibling declaration in the same
 * container (regardless of source order, matching Zig's own container semantics); and one further `.name`
 * step onto an `@import(...)`-aliased identifier. Anything needing actual type information (a struct
 * instance's field, a generic parameter, a `usingnamespace`d name) is out of scope and simply does not
 * resolve, same as before this existed.
 */
object ZigDeclarationResolver {

    /** The declaration a bare identifier expression [usage] (an [ZigPrimaryTypeExpr]'s identifier) names, or null. */
    fun resolveName(usage: PsiElement, name: String): PsiElement? {
        resolveParameter(usage, name)?.let { return it }
        resolveLocalVar(usage, name)?.let { return it }
        return resolveContainerSibling(usage, name)
    }

    /** The declaration `base.field` (a [ZigSuffixOp]'s identifier) names, when `base` is an `@import`ed name. */
    fun resolveField(op: ZigSuffixOp, fieldName: String): PsiElement? {
        val base = op.prevSibling(skippingWhitespace = true) ?: return null
        val baseId = (base as? ZigPrimaryTypeExpr)?.identifier ?: return null
        val target = resolveName(base, baseId.text) ?: return null
        val importPath = importTargetOf(target) ?: return null
        val projects = op.project.zigBuildScan.projects
        val fromFile = op.containingFile.virtualFile?.toNioPath() ?: return null
        val resolvedFile = ZigImportResolution.resolve(projects, fromFile, importPath) ?: return null
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(resolvedFile) ?: return null
        val psiFile = PsiManager.getInstance(op.project).findFile(virtualFile) ?: return null
        val members = PsiTreeUtil.getChildOfType(psiFile, ZigContainerMembers::class.java) ?: return null
        return declInContainer(members, fieldName)
    }

    // Block, ParamDeclList are usage's ancestor and Decl's sibling respectively (Decl ::= ... FnProto
    // (SEMICOLON | Block)) - FnProto is never an ancestor of anything in the function body.
    private fun resolveParameter(usage: PsiElement, name: String): PsiElement? {
        val fnProto = PsiTreeUtil.getParentOfType(usage, ZigDecl::class.java)?.fnProto ?: return null
        return fnProto.paramDeclList?.paramDeclList
            ?.firstOrNull { it.identifier?.text == name }
            ?.identifier
    }

    // Order-sensitive, unlike a container: a local only exists for code textually after it in the same or
    // an enclosing block.
    private fun resolveLocalVar(usage: PsiElement, name: String): PsiElement? {
        var block = PsiTreeUtil.getParentOfType(usage, ZigBlock::class.java)
        while (block != null) {
            for (statement in block.statementList) {
                if (statement.textOffset >= usage.textOffset) break
                val decl = PsiTreeUtil.findChildOfType(statement, ZigVarDeclExprStatement::class.java, false)
                decl?.varDeclProtoList?.firstOrNull { it.identifier?.text == name }?.identifier?.let { return it }
            }
            block = PsiTreeUtil.getParentOfType(block, ZigBlock::class.java)
        }
        return null
    }

    // Zig containers (structs, files) see every declaration regardless of source order.
    private fun resolveContainerSibling(usage: PsiElement, name: String): PsiElement? {
        val members = PsiTreeUtil.getParentOfType(usage, ZigContainerMembers::class.java) ?: return null
        return declInContainer(members, name)
    }

    private fun declInContainer(members: ZigContainerMembers, name: String): PsiElement? {
        for (decl in members.containerDeclarationList) {
            decl.decl?.fnProto?.let { if (it.identifier?.text == name) return it.identifier }
            decl.decl?.globalVarDecl?.varDeclProto?.let { if (it.identifier?.text == name) return it.identifier }
        }
        return null
    }

    /** [target] is a `VarDeclProto` identifier; the `@import("...")` string it is assigned, if any. */
    private fun importTargetOf(target: PsiElement): String? {
        val globalVarDecl = PsiTreeUtil.getParentOfType(target, ZigGlobalVarDecl::class.java) ?: return null
        val expr = globalVarDecl.expr ?: return null
        // `@import(...)` with nothing else around it (the usual shape) parses straight to a
        // PrimaryTypeExpr, not a wrapper containing one - findChildOfType alone would miss it, since it
        // only ever looks at descendants.
        val call = expr as? ZigPrimaryTypeExpr ?: PsiTreeUtil.findChildOfType(expr, ZigPrimaryTypeExpr::class.java) ?: return null
        if (call.builtinidentifier?.text != "@import") return null
        val arg = call.fnCallArguments?.exprList?.exprList?.firstOrNull() ?: return null
        val literal = PsiTreeUtil.findChildOfType(arg, ZigStringLiteral::class.java) ?: return null
        return literal.decodeTextContent().removeSurrounding("\"")
    }

    private fun PsiElement.prevSibling(skippingWhitespace: Boolean): PsiElement? {
        var sibling = prevSibling
        while (skippingWhitespace && sibling is PsiWhiteSpace) sibling = sibling.prevSibling
        return sibling
    }
}
