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

package com.falsepattern.zigbrains.project.execution.base

import com.falsepattern.zigbrains.ZigBrainsBundle
import com.falsepattern.zigbrains.project.execution.ZigConsoleBuilder
import com.falsepattern.zigbrains.project.toolchain.ZigToolchainService
import com.falsepattern.zigbrains.project.toolchain.base.ZigToolchain
import com.falsepattern.zigbrains.shared.cli.startIPCAwareProcess
import com.falsepattern.zigbrains.shared.coroutine.runModalOrBlocking
import com.falsepattern.zigbrains.shared.sameRealPath
import com.falsepattern.zigbrains.shared.sanitizedPathString
import com.intellij.execution.Executor
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.ide.progress.ModalTaskOwner

abstract class ZigProfileState<T: ZigExecConfig<T>> (
    environment: ExecutionEnvironment,
    val configuration: T
): CommandLineState(environment) {

    init {
        consoleBuilder = ZigConsoleBuilder(environment.project, true)
    }

    // Covers the plain-Run path (default CommandLineState.execute()); a plain ConsoleView is never cleared or
    // replayed, unlike an SMTRunnerConsoleView, so printing straight into it is safe. Test/Build build their
    // own SM console instead and thread workingDirectoryLine() through as a recorded SYSTEM line of their own
    // (see ZigProfileStateTest, ZigProfileStateBuild): a plain console.print there would be attached to
    // whichever test node happens to be selected when it runs, and vanish once the selection changes.
    override fun createConsole(executor: Executor): ConsoleView? {
        val console = super.createConsole(executor) ?: return null
        workingDirectoryLine()?.let { console.print("$it\n", ConsoleViewContentType.SYSTEM_OUTPUT) }
        return console
    }

    /** Only worth telling the user about when it isn't where they'd assume (the IDE project directory). Debug
     *  and the Profiler don't go through this console path at all, so they never get this line. */
    fun workingDirectoryLine(): String? {
        val workDir = configuration.workingDirectory.path ?: return null
        val projectDir = environment.project.guessProjectDir()?.toNioPathOrNull() ?: return null
        if (sameRealPath(workDir, projectDir)) return null
        return "working directory: $workDir"
    }

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler {
        return runModalOrBlocking({ModalTaskOwner.project(environment.project)}, {"ZigProfileState.startProcess"}) {
            startProcessSuspend()
        }
    }

    @Throws(ExecutionException::class)
    suspend fun startProcessSuspend(): ProcessHandler {
        val toolchain = ZigToolchainService.getInstance(environment.project).toolchain ?: throw ExecutionException(ZigBrainsBundle.message("exception.zig-profile-state.start-process.no-toolchain"))
        return getCommandLine(toolchain, false).startIPCAwareProcess(environment.project, emulateTerminal = true)
    }

    @Throws(ExecutionException::class)
    open suspend fun getCommandLine(toolchain: ZigToolchain, debug: Boolean): GeneralCommandLine {
        return createCommandLine(toolchain, PtyCommandLine().withConsoleMode(false), configuration.buildCommandLineArgs(debug))
    }

    @Throws(ExecutionException::class)
    protected suspend fun createCommandLine(toolchain: ZigToolchain, cli: GeneralCommandLine, args: List<String>): GeneralCommandLine {
        val workingDir = configuration.workingDirectory
        val zigExePath = toolchain.zig.path().sanitizedPathString ?: throw ExecutionException("Failed to parse executable path")

        cli.withExePath(zigExePath)
        workingDir.path?.let { cli.withWorkingDirectory(it) }
        cli.withCharset(Charsets.UTF_8)
        cli.addParameters(args)
        return configuration.patchCommandLine(cli)
    }
}
