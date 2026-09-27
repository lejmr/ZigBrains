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
import com.falsepattern.zigbrains.project.execution.firstConfigFactory
import com.falsepattern.zigbrains.zig.psi.ZigFile
import com.falsepattern.zigbrains.zig.psi.ZigTestDecl
import com.falsepattern.zigbrains.zig.psi.ZigTypes
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import java.nio.file.Path
import javax.swing.Icon

// Offers "run/debug all tests in this file" on the gutter icon of the file's first top-level test,
// merged by the platform into the same popup as the per-test actions (RunLineMarkerProvider merges
// every contributor's Info for the same PsiElement into one gutter icon and action group).
class ZigLineMarkerTestFile : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.elementType != ZigTypes.KEYWORD_TEST)
            return null
        val file = element.containingFile as? ZigFile ?: return null
        if (file.firstTopLevelTestKeyword() !== element)
            return null
        // An anonymous `test {}` block already offers "all tests in <file>" through the test producer
        if (element.zbResolveParentTestDecl()?.let { it.identifier == null && it.stringLiteralSingle == null } == true)
            return null
        val virtualFile = file.virtualFile ?: return null
        val allTestsName = ZigBrainsBundle.message("configuration.test.marker-all-name", virtualFile.name)
        val actions = arrayOf<AnAction>(
            RunAllTestsInFileAction(
                virtualFile,
                allTestsName,
                DefaultRunExecutor.getRunExecutorInstance(),
                ZigBrainsBundle.message("action.run-all-tests-in-file", allTestsName),
                AllIcons.Actions.Execute
            ),
            RunAllTestsInFileAction(
                virtualFile,
                allTestsName,
                DefaultDebugExecutor.getDebugExecutorInstance(),
                ZigBrainsBundle.message("action.debug-all-tests-in-file", allTestsName),
                AllIcons.Actions.StartDebugger
            )
        )
        return Info(AllIcons.RunConfigurations.TestState.Run_run, actions, null)
    }
}

private fun ZigFile.firstTopLevelTestKeyword(): PsiElement? {
    val marker = ZigLineMarkerTest()
    return PsiTreeUtil.findChildrenOfType(this, ZigTestDecl::class.java)
        .asSequence()
        .mapNotNull { it.firstChild }
        .firstOrNull { it.elementType == ZigTypes.KEYWORD_TEST && marker.elementMatches(it) }
}

private class RunAllTestsInFileAction(
    private val file: VirtualFile,
    private val configName: String,
    private val executor: Executor,
    text: String,
    icon: Icon
) : AnAction(text, null, icon) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val path = file.toNioPathOrNull() ?: return
        val manager = RunManager.getInstance(project)
        val settings = findExistingConfig(manager, path) ?: createConfig(manager, path)
        manager.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }

    private fun findExistingConfig(manager: RunManager, path: Path): RunnerAndConfigurationSettings? {
        for (settings in manager.getConfigurationSettingsList(ZigConfigTypeTest::class.java)) {
            val config = settings.configuration as? ZigExecConfigTest ?: continue
            if (config.filePath.path != path) continue
            if (config.compilerArgs.args.isNotEmpty()) continue
            return settings
        }
        return null
    }

    private fun createConfig(manager: RunManager, path: Path): RunnerAndConfigurationSettings {
        val settings = manager.createConfiguration(configName, firstConfigFactory<ZigConfigTypeTest>())
        val config = settings.configuration as ZigExecConfigTest
        config.filePath.path = path
        config.compilerArgs.args = ""
        manager.addConfiguration(settings)
        return settings
    }
}
