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

import com.intellij.codeInsight.hints.VcsCodeVisionLanguageContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import java.awt.event.MouseEvent

/**
 * Shows the VCS "code author" inlay (clicking it opens Annotate with Git Blame)
 * above functions, tests and container type declarations.
 */
class ZigVcsCodeVisionContext: VcsCodeVisionLanguageContext {
    override fun isAccepted(element: PsiElement): Boolean {
        val body = declarationBody(element) ?: return false
        return declarationKind(body) != ZigDeclarationKind.COMPTIME
    }

    override fun handleClick(mouseEvent: MouseEvent, editor: Editor, element: PsiElement) {
        // The platform already opens the annotation on click
    }

    override fun computeEffectiveRange(element: PsiElement): TextRange {
        // Skip `pub`/`export`, so a rewritten declaration is shown as new
        val body = declarationBody(element) ?: return element.textRange
        return TextRange(body.textRange.startOffset, element.textRange.endOffset)
    }
}
