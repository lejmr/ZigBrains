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

package com.falsepattern.zigbrains.project.execution.test

import com.falsepattern.zigbrains.zig.psi.ZigFile
import com.falsepattern.zigbrains.zig.psi.ZigTestDecl
import com.falsepattern.zigbrains.zig.util.unescape
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

/**
 * Resolves the location URLs that [ZigTestEventsConverter] attaches to each test
 * ("zigtest://<absolute file path>::<fullName>") back to the [ZigTestDecl] PSI element they came from, so
 * double-clicking a test in the tree navigates to its declaration.
 */
object ZigTestLocator : SMTestLocator {
    const val PROTOCOL = "zigtest"

    fun url(file: String, fullName: String): String = "$PROTOCOL://$file::$fullName"

    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != PROTOCOL) return emptyList()
        // indexOf, not lastIndexOf: the file path itself won't contain "::", but an exotic @"..." test
        // name could.
        val separatorIndex = path.indexOf("::")
        if (separatorIndex < 0) return emptyList()
        val runningFile = Path.of(path.substring(0, separatorIndex))
        val fullName = path.substring(separatorIndex + 2)

        // A test's module path is only ever the running file itself, or (when reached via @import) some
        // other file relative to it -- never both, so figure out which one first instead of searching the
        // running file unconditionally.
        val modulePath = modulePathOf(fullName) ?: return emptyList()
        val fileStem = runningFile.fileName?.toString()?.removeSuffix(".zig")
        val targetFile = if (modulePath == fileStem) {
            runningFile
        } else {
            runningFile.resolveSibling(modulePath.replace('.', '/') + ".zig")
        }

        val decl = findDecl(project, targetFile, fullName) ?: return emptyList()
        return listOf(PsiLocation(project, decl))
    }

    private fun findDecl(project: Project, file: Path, fullName: String): ZigTestDecl? {
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(file) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? ZigFile ?: return null
        val decls = PsiTreeUtil.findChildrenOfType(psiFile, ZigTestDecl::class.java)

        val named = NAMED_TEST.matchEntire(fullName)
        if (named != null) {
            val (_, kind, displayName) = named.destructured
            return if (kind == "decltest") {
                decls.firstOrNull { it.identifier?.text == displayName }
            } else {
                decls.firstOrNull {
                    it.stringLiteralSingle?.text?.removeSurrounding("\"")?.unescape(false)?.toString() == displayName
                }
            }
        }

        val anonymous = ANONYMOUS_TEST.matchEntire(fullName) ?: return null
        val index = anonymous.groupValues[2].toInt()
        return decls.filter { it.identifier == null && it.stringLiteralSingle == null }.getOrNull(index)
    }

    private fun modulePathOf(fullName: String): String? {
        NAMED_TEST.matchEntire(fullName)?.let { return it.groupValues[1] }
        ANONYMOUS_TEST.matchEntire(fullName)?.let { return it.groupValues[1] }
        return null
    }

    // A named test declared with an identifier ("test foo { ... }", attached to an existing declaration)
    // is printed by zig as "<module>.decltest.<name>", not "<module>.test.<name>"; capture which one it
    // was so a string-literal name is only ever matched against "test" and an identifier only against
    // "decltest" (a decltest's name is never a string literal, and vice versa).
    private val NAMED_TEST = Regex("""^(.+?)\.(test|decltest)\.(.+)$""")
    private val ANONYMOUS_TEST = Regex("""^(.+)\.test_(\d+)$""")
}
