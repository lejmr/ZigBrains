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

import com.falsepattern.zigbrains.zig.psi.ZigContainerDeclAuto
import com.falsepattern.zigbrains.zig.psi.ZigContainerDeclaration
import com.falsepattern.zigbrains.zig.psi.ZigContainerMembers
import com.falsepattern.zigbrains.zig.psi.ZigFile
import com.falsepattern.zigbrains.zig.psi.ZigTestDecl
import com.falsepattern.zigbrains.zig.psi.ZigTypes
import com.falsepattern.zigbrains.zig.util.unescape
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.FileUrlProvider
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

/**
 * Resolves the location URLs that [ZigTestEventsConverter] attaches to each test
 * ("zigtest://<absolute file path>::<fullName>") back to the [ZigTestDecl] PSI element they came from, so
 * double-clicking a test in the tree navigates to its declaration.
 */
object ZigTestLocator : SMTestLocator, DumbAware {
    const val PROTOCOL = "zigtest"

    fun url(file: String, fullName: String): String = "$PROTOCOL://$file::$fullName"

    /** Inverse of [url], for a whole url or just its path after `zigtest://`: (running file, fullName), or null. */
    fun parse(url: String): Pair<Path, String>? {
        val path = url.removePrefix("$PROTOCOL://")
        // indexOf, not lastIndexOf: the file path itself won't contain "::", but an exotic @"..." test
        // name could.
        val separatorIndex = path.indexOf("::")
        if (separatorIndex < 0) return null
        return Path.of(path.substring(0, separatorIndex)) to path.substring(separatorIndex + 2)
    }

    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol == "file") {
            return FileUrlProvider.INSTANCE.getLocation(protocol, path, project, scope)
        }
        if (protocol != PROTOCOL) return emptyList()
        val (runningFile, fullName) = parse(path) ?: return emptyList()

        val decl = resolveDecl(project, runningFile, fullName) ?: return emptyList()
        // Point at the `test` keyword rather than the whole declaration: that is the element the
        // gutter producer (ZigConfigProducerTest) understands, so "Run"/"Debug" on a node of the
        // test tree can create the same per-test configuration the gutter icon would.
        val keyword = decl.node.findChildByType(ZigTypes.KEYWORD_TEST)?.psi ?: decl
        return listOf(PsiLocation(project, keyword))
    }

    // zig names a test after the containers it is nested in ("<module>[.<container>]*.<kind>.<name>" or
    // "..._N" for an anonymous one). The scope may itself contain a literal "test"/"decltest" segment
    // (a container named that), so every occurrence of the delimiter is tried until one resolves.
    private fun resolveDecl(project: Project, runningFile: Path, fullName: String): ZigTestDecl? {
        for (match in NAMED_DELIM.findAll(fullName)) {
            val scope = fullName.substring(0, match.range.first)
            val kind = match.groupValues[1]
            val name = fullName.substring(match.range.last + 1)
            val container = resolveScope(project, runningFile, scope) ?: continue
            val decls = directTestDecls(container)
            val found = if (kind == "decltest") {
                decls.firstOrNull { it.identifier?.text == name }
            } else {
                decls.firstOrNull { it.stringLiteralSingle?.text?.removeSurrounding("\"")?.unescape(false)?.toString() == name }
            }
            if (found != null) return found
        }
        val anonymous = ANON_DELIM.find(fullName) ?: return null
        val scope = fullName.substring(0, anonymous.range.first)
        val index = anonymous.groupValues[1].toInt()
        val container = resolveScope(project, runningFile, scope) ?: return null
        return directTestDecls(container).filter { it.identifier == null && it.stringLiteralSingle == null }.getOrNull(index)
    }

    // Resolves a dotted scope path to the file or nested container it names: the longest dot-prefix
    // that is an existing file (the running file's stem, or a sibling "<prefix as dirs>.zig"), with the
    // remaining segments walked as `const <Name> = struct { ... }` containers inside it.
    private fun resolveScope(project: Project, runningFile: Path, scope: String): PsiElement? {
        val segments = scope.split('.')
        for (len in segments.size downTo 1) {
            val filePrefix = segments.subList(0, len).joinToString(".")
            val psiFile = resolveFile(project, runningFile, filePrefix) ?: continue
            var container: PsiElement = psiFile
            for (i in len until segments.size) {
                container = findContainer(container, segments[i]) ?: return null
            }
            return container
        }
        return null
    }

    private fun resolveFile(project: Project, runningFile: Path, prefix: String): ZigFile? {
        val fileStem = runningFile.fileName?.toString()?.removeSuffix(".zig")
        val candidate = if (prefix == fileStem) runningFile else runningFile.resolveSibling(prefix.replace('.', '/') + ".zig")
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(candidate) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile) as? ZigFile
    }

    private fun findContainer(scope: PsiElement, name: String): ZigContainerDeclAuto? {
        for (decl in containerDeclarationsOf(scope)) {
            val globalVarDecl = decl.decl?.globalVarDecl ?: continue
            if (globalVarDecl.varDeclProto.identifier?.text != name) continue
            val expr = globalVarDecl.expr ?: continue
            return PsiTreeUtil.findChildOfType(expr, ZigContainerDeclAuto::class.java)
        }
        return null
    }

    private fun directTestDecls(scope: PsiElement): List<ZigTestDecl> {
        return containerDeclarationsOf(scope).mapNotNull { it.testDecl }
    }

    private fun containerDeclarationsOf(scope: PsiElement): List<ZigContainerDeclaration> {
        val members = when (scope) {
            is ZigFile -> PsiTreeUtil.getChildOfType(scope, ZigContainerMembers::class.java)
            is ZigContainerDeclAuto -> scope.containerMembers
            else -> null
        }
        return members?.containerDeclarationList ?: emptyList()
    }

    // A named test declared with an identifier ("test foo { ... }", attached to an existing declaration)
    // is printed by zig as "<scope>.decltest.<name>", not "<scope>.test.<name>".
    private val NAMED_DELIM = Regex("""\.(test|decltest)\.""")
    private val ANON_DELIM = Regex("""\.test_(\d+)$""")
}
