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

import com.intellij.execution.Executor
import com.intellij.icons.AllIcons
import com.intellij.openapi.wm.ToolWindowId
import javax.swing.Icon

/**
 * A persistent toolbar icon next to Run/Debug/"Run with Coverage", registered the same way those platform
 * executors are (`com.intellij.executor` EP). [ZigProfilerProgramRunner] is the `ProgramRunner` that answers
 * it; the profiling result reuses the Run tool window's console, the same one [ZigProfilerService] already
 * shows for the gutter action.
 */
class ZigProfileExecutor : Executor() {
    override fun getStartActionText(): String = ProfilerBundle.message("executor.profile.action-name")

    override fun getToolWindowId(): String = ToolWindowId.RUN

    override fun getToolWindowIcon(): Icon = AllIcons.Actions.Profile

    override fun getIcon(): Icon = AllIcons.Actions.Profile

    override fun getDisabledIcon(): Icon? = null

    override fun getDescription(): String = ProfilerBundle.message("executor.profile.description")

    override fun getActionName(): String = ProfilerBundle.message("executor.profile.action-name")

    override fun getId(): String = EXECUTOR_ID

    override fun getContextActionId(): String = "ZigProfileExecutor"

    override fun getHelpId(): String? = null

    companion object {
        const val EXECUTOR_ID = "Profile"
    }
}
