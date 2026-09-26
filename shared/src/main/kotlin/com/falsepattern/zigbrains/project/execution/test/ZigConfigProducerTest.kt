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

import com.falsepattern.zigbrains.ZigBrainsBundle
import com.falsepattern.zigbrains.project.execution.base.ZigConfigProducer
import com.falsepattern.zigbrains.project.execution.firstConfigFactory
import com.falsepattern.zigbrains.zig.psi.ZigFile
import com.falsepattern.zigbrains.zig.psi.ZigTestDecl
import com.falsepattern.zigbrains.zig.util.escape
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

class ZigConfigProducerTest: ZigConfigProducer<ZigExecConfigTest>() {
    override fun getConfigurationFactory(): ConfigurationFactory {
        return firstConfigFactory<ZigConfigTypeTest>()
    }

    override fun setupConfigurationFromContext(configuration: ZigExecConfigTest, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val location = context.psiLocation
        val directory = location as? PsiDirectory
        if (directory != null) {
            return setupFromDirectory(configuration, directory)
        }
        if (setupFromWholeFile(configuration, location)) {
            return true
        }
        return super.setupConfigurationFromContext(configuration, context, sourceElement)
    }

    override fun isConfigurationFromContext(configuration: ZigExecConfigTest, context: ConfigurationContext): Boolean {
        val location = context.psiLocation
        val directory = location as? PsiDirectory
        if (directory != null) {
            return isFromDirectory(configuration, directory)
        }
        if (isFromWholeFile(configuration, location)) {
            return true
        }
        return super.isConfigurationFromContext(configuration, context)
    }

    private fun setupFromDirectory(configuration: ZigExecConfigTest, directory: PsiDirectory): Boolean {
        val dirFile = directory.virtualFile
        val dirPath = dirFile.toNioPathOrNull() ?: return false
        if (!dirFile.containsZigFileRecursively()) return false
        configuration.filePath.path = dirPath
        configuration.name = cfgName(null, dirFile.name)
        return true
    }

    private fun isFromDirectory(configuration: ZigExecConfigTest, directory: PsiDirectory): Boolean {
        val dirFile = directory.virtualFile
        val dirPath = dirFile.toNioPathOrNull() ?: return false
        if (!dirFile.containsZigFileRecursively()) return false
        return configuration.filePath.path == dirPath && configuration.name == cfgName(null, dirFile.name)
    }

    // Whole-file fallback: the caret/selection is somewhere in a ZigFile that is not on/inside any
    // test declaration (that case is handled by the element-based producer below, via super).
    private fun setupFromWholeFile(configuration: ZigExecConfigTest, location: PsiElement?): Boolean {
        val (psiFile, filePath, theFile) = resolveWholeFileTarget(location) ?: return false
        if (PsiTreeUtil.findChildOfType(psiFile, ZigTestDecl::class.java) == null) return false
        configuration.filePath.path = filePath
        configuration.name = cfgName(null, theFile.presentableName)
        return true
    }

    private fun isFromWholeFile(configuration: ZigExecConfigTest, location: PsiElement?): Boolean {
        val (psiFile, filePath, theFile) = resolveWholeFileTarget(location) ?: return false
        if (PsiTreeUtil.findChildOfType(psiFile, ZigTestDecl::class.java) == null) return false
        return configuration.filePath.path == filePath && configuration.name == cfgName(null, theFile.presentableName)
    }

    private fun resolveWholeFileTarget(location: PsiElement?): Triple<ZigFile, Path, VirtualFile>? {
        if (location == null) return null
        val psiFile = location.containingFile as? ZigFile ?: return null
        if (PsiTreeUtil.getParentOfType(location, ZigTestDecl::class.java, false) != null) return null
        val theFile = psiFile.virtualFile ?: return null
        val filePath = theFile.toNioPathOrNull() ?: return null
        return Triple(psiFile, filePath, theFile)
    }

    private fun VirtualFile.containsZigFileRecursively(): Boolean {
        var found = false
        VfsUtilCore.iterateChildrenRecursively(this, null) { child ->
            if (!child.isDirectory && child.extension == "zig") {
                found = true
                false
            } else {
                true
            }
        }
        return found
    }

    override fun setupConfigurationFromContext(configuration: ZigExecConfigTest, element: PsiElement, psiFile: ZigFile, filePath: Path, theFile: VirtualFile): Boolean {
        val element = element.zbResolveParentTestDecl() ?: return false

        val name = testName(element)
        configuration.filePath.path = filePath
        configuration.name = cfgName(name, theFile.presentableName)
        if (name != null) {
            configuration.compilerArgs.args = "--test-filter \"$name\""
        }
        return true
    }

    override fun isConfigurationFromContext(configuration: ZigExecConfigTest, element: PsiElement, psiFile: ZigFile, filePath: Path, theFile: VirtualFile): Boolean {
        val element = element.zbResolveParentTestDecl() ?: return false
        if (filePath != configuration.filePath.path)
            return false
        val name = testName(element)
        val cfgName = cfgName(name, theFile.presentableName)
        return configuration.name == cfgName
    }

    private fun testName(element: ZigTestDecl): String? {
        return element.identifier?.text?.escape()?.toString()
            ?: element.stringLiteralSingle?.text?.trim('"')?.escape()?.toString()
    }

    private fun cfgName(testName: String?, fileName: String): String {
        return if (testName == null) {
            ZigBrainsBundle.message("configuration.test.marker-all-name", fileName)
        } else {
            ZigBrainsBundle.message("configuration.test.marker-name", testName, fileName)
        }
    }

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
        return self.configurationType is ZigConfigTypeTest
    }
}