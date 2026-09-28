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

package com.falsepattern.zigbrains.zig.documentation

import com.falsepattern.zigbrains.zig.psi.ZigContainerDeclaration
import com.falsepattern.zigbrains.zig.psi.ZigContainerField
import com.falsepattern.zigbrains.zig.psi.ZigFile
import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Quick Documentation for a `///` doc comment on a `fn`/`const`/`var` declaration or a struct/enum/union
 * field, the same as Rust's `///` shows in RustRover: hovering (or Ctrl+Q on) anywhere in the declaration
 * itself renders its leading `///` block as markdown, mirroring what
 * [ZigTopLevelDocumentationProvider] already does for a file's `//!` block.
 */
class ZigDeclDocumentationProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (file !is ZigFile) return emptyList()
        val elem = file.findElementAt(offset) ?: return emptyList()

        PsiTreeUtil.getParentOfType(elem, ZigContainerField::class.java, false)?.let { field ->
            field.docComment?.let { doc -> return target(doc.text, field.identifier?.text ?: field.text) }
        }
        PsiTreeUtil.getParentOfType(elem, ZigContainerDeclaration::class.java, false)?.let { decl ->
            val doc = decl.docComment ?: return emptyList()
            val name = decl.decl?.fnProto?.identifier ?: decl.decl?.globalVarDecl?.varDeclProto?.identifier
            return target(doc.text, name?.text ?: decl.text.lineSequence().first())
        }
        return emptyList()
    }

    private fun target(rawText: String, presentableName: String): List<DocumentationTarget> = listOf(object : DocumentationTarget {
        override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

        override fun computeDocumentation(): DocumentationResult = DocumentationResult.asyncDocumentation {
            DocumentationResult.documentation(convertMarkdownToHtml(stripDocCommentPrefix(rawText, "///")))
        }

        override fun computePresentation(): TargetPresentation = TargetPresentation.builder(presentableName).presentation()
    })
}
