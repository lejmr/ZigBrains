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

package com.falsepattern.zigbrains.project.execution.build

import com.falsepattern.zigbrains.ZigBrainsBundle
import com.falsepattern.zigbrains.project.execution.base.ZigConfigProducer
import com.falsepattern.zigbrains.project.execution.run.zbResolveMainFnProto
import com.falsepattern.zigbrains.project.execution.base.findBuildZig
import com.falsepattern.zigbrains.project.execution.base.isBuildZig
import com.falsepattern.zigbrains.project.execution.firstConfigFactory
import com.falsepattern.zigbrains.zig.psi.ZigFile
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import java.nio.file.Path

class ZigConfigProducerBuild: ZigConfigProducer<ZigExecConfigBuild>() {
    override fun getConfigurationFactory(): ConfigurationFactory {
        return firstConfigFactory<ZigConfigTypeBuild>()
    }

    override fun setupConfigurationFromContext(configuration: ZigExecConfigBuild, element: PsiElement, psiFile: ZigFile, filePath: Path, theFile: VirtualFile): Boolean {
        if (theFile.isBuildZig()) {
            configuration.name = ZigBrainsBundle.message("configuration.build.marker-run")
            configuration.buildSteps.args = "run"
            configuration.debugBuildSteps.args = ""
            return true
        }
        val buildZig = theFile.findBuildZig() ?: return false
        // A `main` in a project with a build.zig runs through `zig build run`. Debugging needs
        // the installed executable, so the first `addExecutable(.{ .name = "..." })` in
        // build.zig is taken as zig-out/bin/<name>; without it the run configuration asks for it.
        element.zbResolveMainFnProto() ?: return false
        val projectDir = buildZig.parent.toNioPath()
        configuration.workingDirectory.path = projectDir
        configuration.name = ZigBrainsBundle.message("configuration.build.marker-run")
        configuration.buildSteps.args = "run"
        configuration.debugBuildSteps.args = "install"
        executableName(buildZig)?.let { configuration.exePath.path = projectDir.resolve("zig-out").resolve("bin").resolve(it) }
        return true
    }

    private fun executableName(buildZig: VirtualFile): String? {
        val text = runCatching { VfsUtilCore.loadText(buildZig) }.getOrNull() ?: return null
        return zigBuildExecutableName(text)
    }

    override fun isConfigurationFromContext(configuration: ZigExecConfigBuild, element: PsiElement, psiFile: ZigFile, filePath: Path, theFile: VirtualFile): Boolean {
        val dir = configuration.workingDirectory.path ?: return false
        if (theFile.isBuildZig()) {
            return filePath.parent == dir
        }
        element.zbResolveMainFnProto() ?: return false
        val buildZig = theFile.findBuildZig() ?: return false
        return buildZig.parent.toNioPath() == dir && configuration.buildSteps.args == "run"
    }

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
        return self.configurationType is ZigConfigTypeBuild
    }
}

/** The `.name` of the first `addExecutable(.{ ... })` in a build.zig's text, installed as zig-out/bin/<name>. */
fun zigBuildExecutableName(buildZigText: String): String? = EXECUTABLE_NAME.find(buildZigText)?.groupValues?.get(1)

private val EXECUTABLE_NAME = Regex("""addExecutable\s*\(\s*\.\{[^}]*?\.name\s*=\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
