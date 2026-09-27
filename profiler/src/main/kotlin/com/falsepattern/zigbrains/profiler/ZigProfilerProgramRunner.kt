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
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner

/**
 * Answers the [ZigProfileExecutor] toolbar button for any Zig run configuration: same one-liner the gutter
 * action already calls (see [com.falsepattern.zigbrains.profiler.ZigProfileLineMarker]). [ZigProfilerService]
 * builds and shows its own run content (a plain console plus the Zig Profiler tool window), so there is no
 * process handler or [com.intellij.execution.ui.RunContentDescriptor] for the platform to manage here.
 */
class ZigProfilerProgramRunner : ProgramRunner<RunnerSettings> {
    override fun getRunnerId(): String = "ZigProfilerRunner"

    // Same OS gate as the gutter action (see ZigProfileLineMarker): hides the toolbar button rather than
    // failing every click when the platform has no profiler backend at all.
    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == ZigProfileExecutor.EXECUTOR_ID && profile is ZigExecConfig<*> && ProfilerBackend.supported

    override fun execute(environment: ExecutionEnvironment) {
        val settings = environment.runnerAndConfigurationSettings ?: return
        ZigProfilerService.getInstance(environment.project).profile(settings)
    }
}
