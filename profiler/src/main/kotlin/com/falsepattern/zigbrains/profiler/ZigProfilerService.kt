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
import com.falsepattern.zigbrains.project.execution.build.zigBuildExecutableName
import com.falsepattern.zigbrains.project.execution.run.ZigExecConfigRun
import com.falsepattern.zigbrains.project.execution.test.ZigExecConfigTest
import com.falsepattern.zigbrains.project.toolchain.ZigToolchainService
import com.intellij.execution.ExecutionException
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentDescriptorReusePolicy
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Builds a Zig run configuration optimized, records it with the platform's [ProfilerBackend] and shows the result in the Zig Profiler tool window. */
@Service(Service.Level.PROJECT)
class ZigProfilerService(private val project: Project, private val cs: CoroutineScope) {
    fun profile(settings: RunnerAndConfigurationSettings) {
        val config = settings.configuration as? ZigExecConfig<*> ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        val title = ProfilerBundle.message("run.title", settings.name)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val descriptor = RunContentDescriptor(console, null, console.component, title)
        // the descriptor has no process handler, so without this the next run would reuse (and dispose) this tab
        descriptor.reusePolicy = object : RunContentDescriptorReusePolicy() {
            override fun canBeReusedBy(newDescriptor: RunContentDescriptor) = false
        }
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        val job = cs.launch {
            withBackgroundProgress(project, title) {
                var tmp: Path? = null
                var shown = false
                try {
                    tmp = FileUtil.createTempDirectory("zigbrains_profile", "", true).toPath()
                    val recording = record(settings, config, console, tmp)
                    shown = withContext(Dispatchers.EDT) { ZigProfilerView.show(project, settings.name, recording, tmp) }
                } catch (e: CancellationException) {
                    console.print(ProfilerBundle.message("console.cancelled") + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    throw e
                } catch (e: Exception) {
                    console.print("${e.message ?: e}\n", ConsoleViewContentType.ERROR_OUTPUT)
                } finally {
                    if (!shown) tmp?.let { FileUtil.delete(it.toFile()) }
                }
            }
        }
        // closing the run tab stops the profiling
        Disposer.register(descriptor) { job.cancel() }
    }

    private suspend fun record(settings: RunnerAndConfigurationSettings, config: ZigExecConfig<*>, console: ConsoleView, tmp: Path): Recording {
        val backend = ProfilerBackend.current
        // may run `perf --version` / `xcrun --find`, so off the EDT
        withContext(Dispatchers.IO) { backend.unavailableReason() }?.let { throw ExecutionException(it) }
        val toolchain = ZigToolchainService.getInstance(project).toolchain
            ?: throw ExecutionException(ProfilerBundle.message("error.no-toolchain"))

        val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
        val pty = config.getState(env.executor, env).getCommandLine(toolchain, true)
        val params = pty.parametersList
        val exe: Path
        val exeArgs: List<String>
        if (config is ZigExecConfigBuild) {
            // `zig build <debug steps>` installs the binary the debugger would use; the option goes right after `build`
            val optimize = params.list.firstOrNull { it.startsWith("-Doptimize") || it.startsWith("--release") }
            if (optimize == null) params.addAt(1, "-Doptimize=ReleaseSafe")
            else if (optimize == "-Doptimize=Debug") console.print(ProfilerBundle.message("console.debug-warning") + "\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            console.print(ProfilerBundle.message("console.build-installs") + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            exe = config.exePath.path ?: defaultBuildExecutable(config) ?: throw ExecutionException(ProfilerBundle.message("error.no-exe-name"))
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
        // the debugger's command line is a PTY one; the plain console wants a plain process
        val build = GeneralCommandLine(pty.getCommandLineList(null))
            .withWorkDirectory(pty.workDirectory)
            .withEnvironment(pty.environment)
            .withCharset(Charsets.UTF_8)
            .withRedirectErrorStream(true)
        val built = console.run(build)
        if (built.exit != 0) {
            if (config is ZigExecConfigBuild && "invalid option: -Doptimize" in built.output)
                console.print(ProfilerBundle.message("console.no-optimize-option") + "\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            throw ExecutionException(ProfilerBundle.message("error.build-failed"))
        }
        if (!exe.exists())
            throw ExecutionException(ProfilerBundle.message("error.no-exe", exe))

        val program = GeneralCommandLine(exe.toString()).withParameters(exeArgs).withWorkingDirectory(config.workingDirectory.path)
        // only a test finishes in a millisecond and is worth repeating; a build's `main` may write files or hit the network
        val recording = backend.record(console, config.patchCommandLine(program), tmp, repeat = config is ZigExecConfigTest)
        console.print(ProfilerBundle.message("console.done", recording.profile.samples) + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        return recording
    }

    /** `<working dir>/zig-out/bin/<name>` from the first `addExecutable` in the working directory's build.zig. */
    private suspend fun defaultBuildExecutable(config: ZigExecConfigBuild): Path? {
        val dir = config.workingDirectory.path ?: return null
        val text = withContext(Dispatchers.IO) { runCatching { dir.resolve("build.zig").readText() }.getOrNull() } ?: return null
        val name = zigBuildExecutableName(text) ?: return null
        return dir.resolve("zig-out").resolve("bin").resolve(name)
    }

    companion object {
        fun getInstance(project: Project): ZigProfilerService = project.service()
    }
}
