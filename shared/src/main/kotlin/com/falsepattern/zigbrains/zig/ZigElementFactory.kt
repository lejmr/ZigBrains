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

package com.falsepattern.zigbrains.zig

import com.falsepattern.zigbrains.zig.psi.ZigFile
import com.falsepattern.zigbrains.zig.psi.ZigVarDeclProto
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil

/** Builds standalone PSI elements out of text, the same way a "rename" needs a replacement identifier leaf. */
object ZigElementFactory {
    /** A bare `IDENTIFIER` token spelled [name], for splicing into an existing tree (e.g. on rename). */
    fun createIdentifier(project: Project, name: String): PsiElement {
        val file = PsiFileFactory.getInstance(project).createFileFromText("dummy.zig", ZigLanguage, "const $name = 0;") as ZigFile
        return PsiTreeUtil.findChildOfType(file, ZigVarDeclProto::class.java)!!.identifier!!
    }
}
