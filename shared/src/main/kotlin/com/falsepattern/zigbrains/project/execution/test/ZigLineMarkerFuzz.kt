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
import com.falsepattern.zigbrains.project.execution.base.findBuildZig
import com.falsepattern.zigbrains.project.execution.build.ZigConfigTypeBuild
import com.falsepattern.zigbrains.project.execution.build.ZigExecConfigBuild
import com.falsepattern.zigbrains.project.execution.firstConfigFactory
import com.falsepattern.zigbrains.zig.psi.ZigTestDecl
import com.falsepattern.zigbrains.zig.psi.ZigTypes
import com.falsepattern.zigbrains.zig.util.unescape
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import java.nio.file.Path

// Offers "fuzz this test" on the gutter icon of a top-level test whose body calls std.testing.fuzz,
// merged by the platform into the same popup as ZigLineMarkerTest's run/debug actions. Zig 0.16 can
// only fuzz through `zig build test --fuzz --webui`, which fuzzes every fuzz test the build graph
// reaches, so this reuses (or creates) one build configuration per package rather than per test.
class ZigLineMarkerFuzz : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.elementType != ZigTypes.KEYWORD_TEST)
            return null
        val decl = element.zbResolveParentTestDecl() ?: return null
        // ponytail: regex over the decl's text instead of walking the PSI for `testing` `.` `fuzz` `(` -
        // cheap and good enough, upgrade to a PSI walk if it ever false-positives on a comment/string.
        if (!FUZZ_CALL.containsMatchIn(decl.text))
            return null
        val virtualFile = element.containingFile?.virtualFile ?: return null
        val buildZig = virtualFile.findBuildZig() ?: return null
        val name = testName(decl) ?: return null
        val action = FuzzTestAction(buildZig.parent.toNioPath(), ZigBrainsBundle.message("action.fuzz-test", name))
        return Info(AllIcons.Actions.Lightning, arrayOf<AnAction>(action), null)
    }

    private fun testName(element: ZigTestDecl): String? {
        return element.identifier?.text
            ?: element.stringLiteralSingle?.text?.removeSurrounding("\"")?.unescape(false)?.toString()
    }
}

private val FUZZ_CALL = Regex("""\btesting\.fuzz\s*\(""")

private class FuzzTestAction(
    private val packageDir: Path,
    text: String
) : AnAction(text, ZigBrainsBundle.message("action.fuzz-test.description"), AllIcons.Actions.Lightning) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = RunManager.getInstance(project)
        val settings = findExistingConfig(manager) ?: createConfig(manager)
        manager.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    private fun findExistingConfig(manager: RunManager): RunnerAndConfigurationSettings? {
        for (settings in manager.getConfigurationSettingsList(ZigConfigTypeBuild::class.java)) {
            val config = settings.configuration as? ZigExecConfigBuild ?: continue
            if (config.workingDirectory.path != packageDir) continue
            if (config.buildSteps.args != FUZZ_BUILD_STEPS) continue
            return settings
        }
        return null
    }

    private fun createConfig(manager: RunManager): RunnerAndConfigurationSettings {
        val configName = ZigBrainsBundle.message("configuration.build.marker-fuzz", packageDir.fileName?.toString() ?: packageDir.toString())
        val settings = manager.createConfiguration(configName, firstConfigFactory<ZigConfigTypeBuild>())
        val config = settings.configuration as ZigExecConfigBuild
        config.workingDirectory.path = packageDir
        config.buildSteps.args = FUZZ_BUILD_STEPS
        config.extraArgs.args = "-Doptimize=ReleaseSafe"
        config.debugBuildSteps.args = ""
        manager.addConfiguration(settings)
        return settings
    }
}

private const val FUZZ_BUILD_STEPS = "test --fuzz --webui"
