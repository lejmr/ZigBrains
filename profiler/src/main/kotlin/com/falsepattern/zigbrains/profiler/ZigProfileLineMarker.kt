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

package com.falsepattern.zigbrains.profiler

import com.falsepattern.zigbrains.project.execution.base.ZigExecConfig
import com.falsepattern.zigbrains.project.execution.run.ZigLineMarkerRun
import com.falsepattern.zigbrains.project.execution.test.ZigLineMarkerTest
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiElement

/** "Profile '<name>'" next to the Run/Debug entries of top-level tests and `main`; same PsiElements, so the popups merge. */
class ZigProfileLineMarker : RunLineMarkerContributor() {
    private val tests = ZigLineMarkerTest()
    private val mains = ZigLineMarkerRun()

    override fun getInfo(element: PsiElement): Info? {
        // no gutter entry where profiling could only fail (Windows, tool not installed); the reason surfaces on the run
        if (ProfilerBackend.current.unavailableReason() != null) return null
        val icon = when {
            tests.elementMatches(element) -> tests.getIcon(element)
            mains.elementMatches(element) -> mains.getIcon(element)
            else -> return null
        }
        return Info(icon, arrayOf(ZigProfileAction()), null)
    }
}

private class ZigProfileAction : AnAction(AllIcons.Actions.Profile) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val settings = ConfigurationContext.getFromContext(e.dataContext, e.place).configuration
        e.presentation.isEnabledAndVisible = settings?.configuration is ZigExecConfig<*>
        if (settings != null) e.presentation.text = ProfilerBundle.message("action.profile", settings.name)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = ConfigurationContext.getFromContext(e.dataContext, e.place).configuration ?: return
        ZigProfilerService.getInstance(project).profile(settings)
    }
}
