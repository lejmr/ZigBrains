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

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream

/** A sampling profiler the IDE can drive: xctrace on macOS, perf on Linux. */
interface ProfilerBackend {
    /** Why profiling cannot run here (tool missing or broken), or null. May spawn a process: call it on an IO thread, never under a read action. */
    fun unavailableReason(): String?

    /** Bundle key of the tool window action that reveals [Recording.trace]. */
    val openTraceKey: String

    /** Runs [program] (already carrying the run configuration's environment) under the profiler, writing into [tmp]. */
    suspend fun record(console: ConsoleView, program: GeneralCommandLine, tmp: Path): Recording

    companion object {
        val current: ProfilerBackend = when {
            SystemInfo.isMac -> XctraceBackend
            SystemInfo.isLinux -> PerfBackend
            else -> Unsupported
        }

        /** Whether this OS has a backend at all; whether its tool is installed is [unavailableReason]'s business at run time. */
        val supported: Boolean get() = current !== Unsupported
    }
}

/** The parsed profile plus what "Open trace" reveals: the .trace bundle on macOS, the folder with perf.data on Linux. */
class Recording(val profile: CpuProfile, val trace: Path)

private object Unsupported : ProfilerBackend {
    override fun unavailableReason() = ProfilerBundle.message("error.unsupported-os")
    override val openTraceKey = "action.open-trace-folder"
    override suspend fun record(console: ConsoleView, program: GeneralCommandLine, tmp: Path) = throw ExecutionException(unavailableReason())
}

private object XctraceBackend : ProfilerBackend {
    // `xcrun --find` spawns a process, so the answer is kept for the session (installing Xcode mid-session needs an IDE restart);
    // only the service evaluates it, from its IO context
    private val found by lazy {
        try {
            ExecUtil.execAndGetOutput(GeneralCommandLine("xcrun", "--find", "xctrace")).exitCode == 0
        } catch (_: ExecutionException) {
            false
        }
    }

    override fun unavailableReason() = if (found) null else ProfilerBundle.message("error.no-xctrace")
    override val openTraceKey = "action.open-instruments"

    override suspend fun record(console: ConsoleView, program: GeneralCommandLine, tmp: Path): Recording {
        val trace = tmp.resolve("profile.trace")
        val recordCli = program.wrap(
            "xcrun", "xctrace", "record", "--no-prompt", "--template", "Time Profiler",
            "--output", trace.toString(), "--target-stdout", "-", "--launch", "--"
        )
        // the program's exit code is its own business, a missing trace is ours
        console.run(recordCli)
        if (!trace.exists())
            throw ExecutionException(ProfilerBundle.message("error.no-trace"))

        val xml = tmp.resolve("time-profile.xml")
        val exportCli = GeneralCommandLine("xcrun", "xctrace", "export", "--input", trace.toString(), "--xpath", XctraceExport.TIME_PROFILE_XPATH, "--output", xml.toString())
        if (console.run(exportCli).exit != 0 || !xml.exists())
            throw ExecutionException(ProfilerBundle.message("error.export-failed"))
        val profile = withContext(Dispatchers.IO) { xml.inputStream().use { XctraceExport.parse(it) } }
        return Recording(profile, trace)
    }
}

private object PerfBackend : ProfilerBackend {
    private const val ADDR2LINE_FAILED = "addr2line configuration failed"

    /** `perf --version` rather than a PATH lookup: Ubuntu's /usr/bin/perf is a wrapper that fails when the kernel's linux-tools package is missing. */
    override fun unavailableReason(): String? {
        val problem = try {
            val out = ExecUtil.execAndGetOutput(GeneralCommandLine("perf", "--version"))
            if (out.exitCode == 0) return null
            (out.stderr + out.stdout).lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "exit code ${out.exitCode}"
        } catch (e: ExecutionException) {
            e.message ?: "not on PATH"
        }
        return ProfilerBundle.message("error.no-perf", problem)
    }

    override val openTraceKey = "action.open-trace-folder"

    override suspend fun record(console: ConsoleView, program: GeneralCommandLine, tmp: Path): Recording {
        // frame pointers are the cheap default; --no-inline keeps perf script away from addr2line, which frame-pointer stacks do not need
        var profile = record(console, program, tmp.resolve("perf.data"), listOf("-g", "-F", "999"), noInline = true)
        if (profile.needsDwarf()) {
            console.print(ProfilerBundle.message("console.perf-dwarf-fallback") + "\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            // DWARF unwinding copies 16 KiB of stack per sample, hence the lower rate; inlined frames come out of it, so inlining stays on
            profile = record(console, program, tmp.resolve("perf-dwarf.data"), listOf("--call-graph", "dwarf,16384", "-F", "499"), noInline = false)
        }
        return Recording(profile, tmp)
    }

    private suspend fun record(console: ConsoleView, program: GeneralCommandLine, data: Path, options: List<String>, noInline: Boolean): CpuProfile {
        val recorded = console.run(program.wrap("perf", "record", *options.toTypedArray(), "-o", data.toString(), "--"))
        if (!data.exists() || data.fileSize() == 0L) {
            if ("perf_event_paranoid" in recorded.output)
                throw ExecutionException(ProfilerBundle.message("error.perf-paranoid"))
            throw ExecutionException(ProfilerBundle.message("error.no-perf-data"))
        }
        var scripted = script(console, data, noInline)
        if (!noInline && ADDR2LINE_FAILED in scripted.output) {
            // perf 6.x exits 0 and prints garbage without binutils; the inlined frames are lost, the stacks are not
            console.print(ProfilerBundle.message("console.perf-no-addr2line") + "\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            scripted = script(console, data, noInline = true)
        }
        if (scripted.exit != 0 || ADDR2LINE_FAILED in scripted.output)
            throw ExecutionException(ProfilerBundle.message("error.perf-script-failed", scripted.stderr.trim().ifEmpty { scripted.stdout.firstLines() }))
        // the whole perf script text sits in memory, about 100 bytes per frame; streaming it would need a line-wise process listener
        val profile = PerfScript.parse(scripted.stdout)
        if (profile.samples == 0 && scripted.stdout.isNotBlank())
            throw ExecutionException(ProfilerBundle.message("error.perf-script-unparsed", scripted.stdout.firstLines()))
        return profile
    }

    /** perf script output is data, not a log: captured, not shown in the console. */
    private suspend fun script(console: ConsoleView, data: Path, noInline: Boolean): StepResult {
        val cli = GeneralCommandLine("perf", "script", "-i", data.toString()).withWorkingDirectory(data.parent)
        if (noInline) cli.addParameter("--no-inline")
        return console.run(cli, show = false)
    }

    private fun String.firstLines() = lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n").trim()
}

/** `tool args... <program>`: the profiler launches the program, so it inherits the program's directory and environment. */
private fun GeneralCommandLine.wrap(vararg tool: String): GeneralCommandLine =
    GeneralCommandLine(tool.toList() + getCommandLineList(null))
        .withWorkDirectory(workDirectory)
        .withEnvironment(environment)
        .withParentEnvironmentType(parentEnvironmentType)

/** Exit code and captured output of one step; [output] is both streams, for messages that may land in either. */
internal class StepResult(val exit: Int, val stdout: String, val stderr: String) {
    val output: String get() = stdout + stderr
}

/**
 * Runs one step, echoing its command line to the console; with [show] the process output streams into the console as well
 * (off for output that is data). Cancelling destroys the process (a soft kill first, which lets xctrace save).
 */
internal suspend fun ConsoleView.run(cli: GeneralCommandLine, show: Boolean = true): StepResult {
    print("${cli.commandLineString}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    val handler = KillableColoredProcessHandler(cli)
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    handler.addProcessListener(object : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            val sink = if (ProcessOutputType.isStderr(outputType)) stderr else stdout
            synchronized(sink) { sink.append(event.text) }
        }
    })
    if (show) attachToProcess(handler)
    handler.startNotify()
    return withContext(Dispatchers.IO) {
        try {
            while (!handler.waitFor(100)) ensureActive()
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
        }
        StepResult(handler.exitCode ?: -1, synchronized(stdout) { stdout.toString() }, synchronized(stderr) { stderr.toString() })
    }
}
