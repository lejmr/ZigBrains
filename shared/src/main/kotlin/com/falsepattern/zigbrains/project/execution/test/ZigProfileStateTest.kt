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
import com.falsepattern.zigbrains.project.execution.base.ZigProfileState
import com.falsepattern.zigbrains.project.toolchain.ZigToolchainService
import com.falsepattern.zigbrains.shared.coroutine.runModalOrBlocking
import com.falsepattern.zigbrains.shared.sanitizedPathString
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.platform.ide.progress.ModalTaskOwner

class ZigProfileStateTest(environment: ExecutionEnvironment, configuration: ZigExecConfigTest) : ZigProfileState<ZigExecConfigTest>(environment, configuration) {
    @Throws(ExecutionException::class)
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        // Only the suspend command-line construction runs under runModalOrBlocking (which may hop
        // off the EDT); the process handler, console and Swing-touching pieces below are built
        // back on the caller's thread, whichever that is.
        val commands = runModalOrBlocking({ ModalTaskOwner.project(environment.project) }, { "ZigProfileStateTest.execute" }) {
            val toolchain = ZigToolchainService.getInstance(environment.project).toolchain
                ?: throw ExecutionException(ZigBrainsBundle.message("exception.zig-profile-state.start-process.no-toolchain"))
            val workingDir = configuration.workingDirectory
            val zigExePath = toolchain.zig.path().sanitizedPathString ?: throw ExecutionException("Failed to parse executable path")

            configuration.testFiles().map { file ->
                val cli = GeneralCommandLine()
                cli.withExePath(zigExePath)
                workingDir.path?.let { cli.withWorkingDirectory(it) }
                cli.withCharset(Charsets.UTF_8)
                cli.addParameters(configuration.buildCommandLineArgs(false, file))
                file to configuration.patchCommandLine(cli)
            }
        }

        val handler = ZigTestProcessHandler(commands)
        ProcessTerminatedListener.attach(handler)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole("Zig Test", handler, ZigTestConsoleProperties(configuration, executor))
        return DefaultExecutionResult(console, handler)
    }
}
