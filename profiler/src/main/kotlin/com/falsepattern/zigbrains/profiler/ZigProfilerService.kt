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
import com.falsepattern.zigbrains.project.execution.build.ZigExecConfigBuild
import com.falsepattern.zigbrains.project.execution.run.ZigExecConfigRun
import com.falsepattern.zigbrains.project.toolchain.ZigToolchainService
import com.intellij.execution.ExecutionException
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

/** Builds a Zig run configuration optimized, records it with `xctrace` and shows the result in the Zig Profiler tool window. */
@Service(Service.Level.PROJECT)
class ZigProfilerService(private val project: Project, private val cs: CoroutineScope) {
    fun profile(settings: RunnerAndConfigurationSettings) {
        val config = settings.configuration as? ZigExecConfig<*> ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        val title = ProfilerBundle.message("run.title", settings.name)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val descriptor = RunContentDescriptor(console, null, console.component, title)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        cs.launch {
            withBackgroundProgress(project, title) {
                try {
                    val (profile, trace) = record(settings, config, console)
                    withContext(Dispatchers.EDT) { ZigProfilerView.show(project, settings.name, profile, trace) }
                } catch (e: ExecutionException) {
                    console.print("${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                }
            }
        }
    }

    private suspend fun record(settings: RunnerAndConfigurationSettings, config: ZigExecConfig<*>, console: ConsoleView): Pair<XctraceProfile, Path> {
        if (!SystemInfo.isMac)
            throw ExecutionException(ProfilerBundle.message("error.mac-only"))
        val found = try {
            withContext(Dispatchers.IO) { ExecUtil.execAndGetOutput(GeneralCommandLine("xcrun", "--find", "xctrace")).exitCode == 0 }
        } catch (_: ExecutionException) {
            false
        }
        if (!found)
            throw ExecutionException(ProfilerBundle.message("error.no-xctrace"))
        val toolchain = ZigToolchainService.getInstance(project).toolchain
            ?: throw ExecutionException(ProfilerBundle.message("error.no-toolchain"))

        val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
        val build = config.getState(env.executor, env).getCommandLine(toolchain, true)
        val params = build.parametersList
        val tmp = FileUtil.createTempDirectory("zigbrains_profile", "", true).toPath()
        val exe: Path
        val exeArgs: List<String>
        if (config is ZigExecConfigBuild) {
            // `zig build <debug steps>` installs the binary the debugger would use
            val optimize = params.list.firstOrNull { it.startsWith("-Doptimize") || it.startsWith("--release") }
            if (optimize == null) params.add("-Doptimize=ReleaseSafe")
            else if (optimize == "-Doptimize=Debug") console.print(ProfilerBundle.message("console.debug-warning") + "\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            exe = config.exePath.path ?: throw ExecutionException(ProfilerBundle.message("error.no-exe-path"))
            exeArgs = config.exeArgs.argsSplit()
        } else {
            // debug args are `zig test|build-exe ... [-O <forced>]`; -O right after the subcommand applies to the root module
            val o = params.list.indexOf("-O")
            if (o < 0) {
                params.addAt(1, "-O")
                params.addAt(2, "ReleaseSafe")
            } else if (params.list.getOrNull(o + 1) == "Debug") {
                console.print(ProfilerBundle.message("console.debug-warning") + "\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            }
            exe = tmp.resolve("executable")
            params.add("-femit-bin=$exe")
            exeArgs = if (config is ZigExecConfigRun) config.exeArgs.argsSplit() else emptyList()
        }
        if (run(console, build) != 0)
            throw ExecutionException(ProfilerBundle.message("error.build-failed"))
        if (!exe.exists())
            throw ExecutionException(ProfilerBundle.message("error.no-exe", exe))

        val trace = tmp.resolve("profile.trace")
        val recordCli = GeneralCommandLine("xcrun", "xctrace", "record", "--template", "Time Profiler", "--output", trace.toString(), "--launch", "--", exe.toString())
            .withParameters(exeArgs)
            .withWorkingDirectory(config.workingDirectory.path)
        // the program's exit code is its own business, a missing trace is ours
        run(console, config.patchCommandLine(recordCli))
        if (!trace.exists())
            throw ExecutionException(ProfilerBundle.message("error.no-trace"))

        val xml = tmp.resolve("time-profile.xml")
        val exportCli = GeneralCommandLine("xcrun", "xctrace", "export", "--input", trace.toString(), "--xpath", XctraceProfile.TIME_PROFILE_XPATH, "--output", xml.toString())
        if (run(console, exportCli) != 0 || !xml.exists())
            throw ExecutionException(ProfilerBundle.message("error.export-failed"))
        val profile = withContext(Dispatchers.IO) { xml.inputStream().use { XctraceProfile.parse(it) } }
        console.print(ProfilerBundle.message("console.done", profile.samples) + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        return profile to trace
    }

    /** Runs one step in the console, echoing its command line; cancelling the progress interrupts it (xctrace saves on SIGINT). */
    private suspend fun run(console: ConsoleView, cli: GeneralCommandLine): Int {
        console.print("${cli.commandLineString}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        val handler = KillableColoredProcessHandler(cli)
        console.attachToProcess(handler)
        handler.startNotify()
        return withContext(Dispatchers.IO) {
            try {
                while (!handler.waitFor(100)) ensureActive()
            } finally {
                if (!handler.isProcessTerminated) handler.destroyProcess()
            }
            handler.exitCode ?: -1
        }
    }

    companion object {
        fun getInstance(project: Project): ZigProfilerService = project.service()
    }
}
