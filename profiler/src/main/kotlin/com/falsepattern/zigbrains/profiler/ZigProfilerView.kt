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
import com.falsepattern.zigbrains.zig.ZigFileType
import com.falsepattern.zigbrains.zig.psi.ZigFnProto
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.table.AbstractTableModel

private const val TOOL_WINDOW_ID = "Zig Profiler"

/** "Profile '<name>'" next to the Run/Debug entries of top-level tests and `main`; same PsiElements, so the popups merge. */
class ZigProfileLineMarker : RunLineMarkerContributor() {
    private val tests = ZigLineMarkerTest()
    private val mains = ZigLineMarkerRun()

    override fun getInfo(element: PsiElement): Info? {
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

/** Registers the "Zig Profiler" tool window; a tab per profiled configuration is added by [ZigProfilerView.show]. */
class ZigProfilerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // tabs are created per profile run
    }

    override fun shouldBeAvailable(project: Project): Boolean = false
}

object ZigProfilerView {
    fun show(project: Project, title: String, profile: XctraceProfile, trace: Path) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        val table = JBTable(Model(profile)).apply { autoCreateRowSorter = true }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = table.selectedRow.takeIf { it >= 0 } ?: return false
                navigate(project, profile.functions[table.convertRowIndexToModel(row)])
                return true
            }
        }.installOn(table)
        val openInInstruments = object : DumbAwareAction(ProfilerBundle.message("action.open-instruments"), null, AllIcons.Actions.MenuOpen) {
            override fun actionPerformed(e: AnActionEvent) {
                GeneralCommandLine("open", trace.toString()).createProcess()
            }
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("ZigProfiler", DefaultActionGroup(openInInstruments), true)
        toolbar.targetComponent = table
        val panel = SimpleToolWindowPanel(true, true).apply {
            this.toolbar = toolbar.component
            setContent(ScrollPaneFactory.createScrollPane(table))
        }
        val manager = toolWindow.contentManager
        manager.findContent(title)?.let { manager.removeContent(it, true) }
        val content = ContentFactory.getInstance().createContent(panel, title, false)
        manager.addContent(content)
        manager.setSelectedContent(content)
        toolWindow.isAvailable = true
        toolWindow.show()
    }

    private class Model(private val profile: XctraceProfile) : AbstractTableModel() {
        private val columns = listOf("column.function", "column.self", "column.total", "column.self-percent").map { ProfilerBundle.message(it) }

        override fun getRowCount() = profile.functions.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(column: Int) = when (column) {
            0 -> String::class.java
            3 -> Double::class.javaObjectType
            else -> Int::class.javaObjectType
        }

        override fun getValueAt(row: Int, column: Int): Any {
            val f = profile.functions[row]
            return when (column) {
                0 -> f.name
                1 -> f.self
                2 -> f.total
                else -> Math.round(f.self * 1000.0 / profile.samples.coerceAtLeast(1)) / 10.0
            }
        }
    }

    /** Goes to the frame's debug-info source line, else to the first `fn <last segment>` in the project's Zig files. */
    private fun navigate(project: Project, f: FunctionStats) {
        ReadAction.nonBlocking<OpenFileDescriptor?> {
            f.file?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.let {
                return@nonBlocking OpenFileDescriptor(project, it, (f.line ?: 1) - 1, 0)
            }
            val name = f.name.substringAfterLast('.')
            // ponytail: walks every project Zig file per double-click; a stub index if that gets slow
            for (file in FileTypeIndex.getFiles(ZigFileType, GlobalSearchScope.projectScope(project))) {
                val psi = PsiManager.getInstance(project).findFile(file) ?: continue
                val fn = PsiTreeUtil.findChildrenOfType(psi, ZigFnProto::class.java).firstOrNull { it.identifier?.textMatches(name) == true }
                if (fn != null) return@nonBlocking OpenFileDescriptor(project, file, fn.identifier!!.textOffset)
            }
            null
        }.finishOnUiThread(ModalityState.defaultModalityState()) { it?.navigate(true) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
