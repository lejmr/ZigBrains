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

import com.falsepattern.zigbrains.zig.ZigFileType
import com.falsepattern.zigbrains.zig.psi.ZigFnProto
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
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

/** Registers the "Zig Profiler" tool window; a tab per profiled configuration is added by [ZigProfilerView.show]. */
class ZigProfilerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // tabs are created per profile run
    }

    override fun shouldBeAvailable(project: Project): Boolean = false
}

object ZigProfilerView {
    /** Adds a tab for [recording]; the tab owns [tmpDir] (trace and export) and deletes it on close. Returns false if the tool window is missing. */
    fun show(project: Project, title: String, recording: Recording, tmpDir: Path): Boolean {
        val profile = recording.profile
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return false
        val table = JBTable(Model(profile)).apply { autoCreateRowSorter = true }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = table.selectedRow.takeIf { it >= 0 } ?: return false
                navigate(project, profile.functions[table.convertRowIndexToModel(row)])
                return true
            }
        }.installOn(table)
        // Instruments opens the .trace bundle on macOS; on Linux the folder with perf.data is revealed for `perf report`
        val openTrace = object : DumbAwareAction(ProfilerBundle.message(ProfilerBackend.current.openTraceKey), null, AllIcons.Actions.MenuOpen) {
            override fun actionPerformed(e: AnActionEvent) {
                RevealFileAction.openFile(recording.trace)
            }
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("ZigProfiler", DefaultActionGroup(openTrace), true)
        toolbar.targetComponent = table
        val panel = SimpleToolWindowPanel(true, true).apply {
            this.toolbar = toolbar.component
            setContent(ScrollPaneFactory.createScrollPane(table))
        }
        val manager = toolWindow.contentManager
        manager.findContent(title)?.let { manager.removeContent(it, true) }
        val content = ContentFactory.getInstance().createContent(panel, title, false)
        Disposer.register(content) { FileUtil.delete(tmpDir.toFile()) }
        manager.addContent(content)
        manager.setSelectedContent(content)
        toolWindow.isAvailable = true
        toolWindow.show()
        return true
    }

    private class Model(private val profile: CpuProfile) : AbstractTableModel() {
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
            // walks every project Zig file per double-click; a stub index would be the upgrade
            for (file in FileTypeIndex.getFiles(ZigFileType, GlobalSearchScope.projectScope(project))) {
                val psi = PsiManager.getInstance(project).findFile(file) ?: continue
                val fn = PsiTreeUtil.findChildrenOfType(psi, ZigFnProto::class.java).firstOrNull { it.identifier?.textMatches(name) == true }
                if (fn != null) return@nonBlocking OpenFileDescriptor(project, file, fn.identifier!!.textOffset)
            }
            null
        }.inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { it?.navigate(true) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
