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
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
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
import kotlin.io.path.inputStream

/** A sampling profiler the IDE can drive: xctrace on macOS, perf on Linux. */
interface ProfilerBackend {
    /** Why profiling is unavailable here (tool missing, unsupported OS), or null when it can run. Cheap: the gutter calls it. */
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
    // `xcrun --find` spawns a process, so the answer is kept; installing Xcode mid-session needs an IDE restart
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
        if (console.run(exportCli).first != 0 || !xml.exists())
            throw ExecutionException(ProfilerBundle.message("error.export-failed"))
        val profile = withContext(Dispatchers.IO) { xml.inputStream().use { XctraceExport.parse(it) } }
        return Recording(profile, trace)
    }
}

private object PerfBackend : ProfilerBackend {
    private val perf by lazy { PathEnvironmentVariableUtil.findInPath("perf")?.path }

    override fun unavailableReason() = if (perf != null) null else ProfilerBundle.message("error.no-perf")
    override val openTraceKey = "action.open-trace-folder"

    override suspend fun record(console: ConsoleView, program: GeneralCommandLine, tmp: Path): Recording {
        val data = tmp.resolve("perf.data")
        var profile = record(console, program, data, "-g")
        // frame pointers are the cheap default; a binary built without them gives one-frame stacks, DWARF unwinding recovers them
        if (profile.functions.none { it.total > it.self }) {
            console.print(ProfilerBundle.message("console.perf-dwarf-fallback") + "\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            profile = record(console, program, data, "--call-graph", "dwarf")
        }
        return Recording(profile, tmp)
    }

    private suspend fun record(console: ConsoleView, program: GeneralCommandLine, data: Path, vararg callGraph: String): CpuProfile {
        console.run(program.wrap(perf!!, "record", *callGraph, "-F", "999", "-o", data.toString(), "--"))
        if (!data.exists())
            throw ExecutionException(ProfilerBundle.message("error.no-perf-data"))
        // perf script output is data, not a log, so it is captured instead of echoed to the console
        val script = GeneralCommandLine(perf!!, "script", "-i", data.toString()).withWorkingDirectory(data.parent)
        console.print("${script.commandLineString}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        val output = withContext(Dispatchers.IO) { ExecUtil.execAndGetOutput(script) }
        if (output.exitCode != 0)
            throw ExecutionException(ProfilerBundle.message("error.perf-script-failed", output.stderr.trim()))
        // ponytail: the whole perf script text sits in memory; stream it if hour-long recordings show up
        return PerfScript.parse(output.stdout)
    }
}

/** `tool args... <program>`: the profiler launches the program, so it inherits the program's directory and environment. */
private fun GeneralCommandLine.wrap(vararg tool: String): GeneralCommandLine =
    GeneralCommandLine(tool.toList() + getCommandLineList(null))
        .withWorkDirectory(workDirectory)
        .withEnvironment(environment)
        .withParentEnvironmentType(parentEnvironmentType)

/** Runs one step in the console, echoing its command line; returns the exit code and the captured output. Cancelling destroys the process (a soft kill first, which lets xctrace save). */
internal suspend fun ConsoleView.run(cli: GeneralCommandLine): Pair<Int, String> {
    print("${cli.commandLineString}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    val handler = KillableColoredProcessHandler(cli)
    val output = StringBuilder()
    handler.addProcessListener(object : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            synchronized(output) { output.append(event.text) }
        }
    })
    attachToProcess(handler)
    handler.startNotify()
    return withContext(Dispatchers.IO) {
        try {
            while (!handler.waitFor(100)) ensureActive()
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
        }
        (handler.exitCode ?: -1) to synchronized(output) { output.toString() }
    }
}
