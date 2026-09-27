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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.readText

/** A sampling profiler the IDE can drive: xctrace on macOS, perf on Linux. */
interface ProfilerBackend {
    /** Why profiling cannot run here (tool missing or broken), or null. May spawn a process: call it on an IO thread, never under a read action. */
    fun unavailableReason(): String?

    /** Bundle key of the tool window action that reveals [Recording.trace]. */
    val openTraceKey: String

    /**
     * Runs [program] (already carrying the run configuration's environment) under the profiler, writing into [tmp].
     * [repeat] asks for [RepeatStrategy]'s loop: a `zig test` that finishes in a millisecond gets looped for real
     * sample counts; run and build configurations pass false and always run once.
     */
    suspend fun record(console: ConsoleView, program: GeneralCommandLine, tmp: Path, repeat: Boolean): Recording

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
    override suspend fun record(console: ConsoleView, program: GeneralCommandLine, tmp: Path, repeat: Boolean) = throw ExecutionException(unavailableReason())
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

    // xctrace prints this once it is actually attached and sampling; a fixed delay measured anywhere from 0 to several seconds too short
    private const val RECORDER_READY_MARKER = "Ctrl-C to stop the recording"

    // generous: startup on a loaded machine can take a couple of seconds, this only guards against the tool hanging
    private const val RECORDER_READY_TIMEOUT_MILLIS = 30_000L

    override suspend fun record(console: ConsoleView, program: GeneralCommandLine, tmp: Path, repeat: Boolean): Recording {
        val single = recordOnce(console, program, tmp)
        // a real recording (long enough, or already stumbled into enough samples) is left as-is; only a too-short one is worth the heavier repeat
        if (!repeat || single.profile.samples >= RepeatStrategy.MIN_SAMPLES_BEFORE_REPEAT) return single
        return try {
            recordRepeated(console, program, tmp)
        } catch (e: ExecutionException) {
            // a handful of samples beats nothing: the single --launch run above already succeeded
            console.print(ProfilerBundle.message("console.repeat-failed", e.message ?: e.toString()) + "\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            single
        }
    }

    private suspend fun recordOnce(console: ConsoleView, program: GeneralCommandLine, tmp: Path): Recording {
        val trace = tmp.resolve("profile.trace")
        val recordCli = program.wrap(
            "xcrun", "xctrace", "record", "--no-prompt", "--template", "Time Profiler",
            "--output", trace.toString(), "--target-stdout", "-", "--launch", "--"
        )
        // the program's exit code is its own business, a missing trace is ours
        console.run(recordCli)
        if (!trace.exists())
            throw ExecutionException(ProfilerBundle.message("error.no-trace"))
        val profile = export(console, trace, tmp, execPath = null)
        return Recording(profile, trace)
    }

    /**
     * `--launch` attributes every sample to the launched process itself, never to a child it forks or execs
     * (verified empirically: a wrapping shell loop gets sampled, the program it repeatedly execs does not), so a
     * short program is instead run directly, several times, under one `--all-processes` recording; [RepeatStrategy]
     * decides when enough wall time has been collected and [XctraceExport]'s execPath filter keeps only its samples.
     */
    private suspend fun recordRepeated(console: ConsoleView, program: GeneralCommandLine, tmp: Path): Recording {
        val trace = tmp.resolve("profile-repeated.trace")
        val recorder = GeneralCommandLine("xcrun", "xctrace", "record", "--no-prompt", "--template", "Time Profiler", "--all-processes", "--output", trace.toString())
        val handler = console.startBackground(recorder, RECORDER_READY_MARKER)
        var runs = 0
        var elapsedNanos = 0L
        try {
            awaitReady(handler, RECORDER_READY_TIMEOUT_MILLIS)
            var lastRunNanos: Long
            do {
                val t0 = System.nanoTime()
                console.run(program, show = runs == 0, quiet = runs > 0)
                lastRunNanos = System.nanoTime() - t0
                runs++
                elapsedNanos += lastRunNanos
            } while (RepeatStrategy.shouldRunAgain(runs, elapsedNanos, lastRunNanos))
        } finally {
            // stop the recorder even on cancellation or a failed/aborted loop, or it keeps sampling the whole machine forever;
            // stopBackground itself runs under NonCancellable, so this finally block completes even in a cancelled coroutine
            console.stopBackground(handler.handler)
        }
        if (!trace.exists())
            throw ExecutionException(ProfilerBundle.message("error.no-trace"))
        val profile = export(console, trace, tmp, execPath = program.exePath.realPathOrSelf())
        if (runs > 1)
            console.print(ProfilerBundle.message("console.repeated", runs, profile.samples) + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        return Recording(profile, trace)
    }

    private suspend fun export(console: ConsoleView, trace: Path, tmp: Path, execPath: String?): CpuProfile {
        val xml = tmp.resolve("time-profile.xml")
        val exportCli = GeneralCommandLine("xcrun", "xctrace", "export", "--input", trace.toString(), "--xpath", XctraceExport.TIME_PROFILE_XPATH, "--output", xml.toString())
        if (console.run(exportCli).exit != 0 || !xml.exists())
            throw ExecutionException(ProfilerBundle.message("error.export-failed"))
        val profile = withContext(Dispatchers.IO) { xml.inputStream().use { XctraceExport.parse(it, execPath) } }
        // a real recording with nothing matching the filter is a silent "0 samples", not a usable profile
        if (execPath != null && profile.samples == 0)
            throw ExecutionException(ProfilerBundle.message("error.no-matching-samples", execPath))
        return profile
    }

    /** xctrace records a binary's canonical path (`$TMPDIR` resolves to `/private/var/folders/...` on macOS); fall back to the raw path if it no longer exists. */
    private fun String.realPathOrSelf(): String = runCatching { Path.of(this).toRealPath().toString() }.getOrDefault(this)
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

    override suspend fun record(console: ConsoleView, program: GeneralCommandLine, tmp: Path, repeat: Boolean): Recording {
        var target = program
        var comm: String? = null
        var runs = 1
        // frame pointers are the cheap default; --no-inline keeps perf script away from addr2line, which frame-pointer stacks do not need
        var profile = record(console, target, tmp.resolve("perf.data"), listOf("-g", "-F", "999"), noInline = true)
        // the first pass already profiled the program for real; only loop if that alone was too short to say much
        if (repeat && profile.samples < RepeatStrategy.MIN_SAMPLES_BEFORE_REPEAT) {
            val countFile = tmp.resolve("repeat-count")
            comm = commOf(program)
            target = loopWrap(program, countFile)
            profile = record(console, target, tmp.resolve("perf-loop.data"), listOf("-g", "-F", "999"), noInline = true, comm = comm, show = false)
            runs = readRunCount(countFile)
        }
        if (profile.needsDwarf()) {
            console.print(ProfilerBundle.message("console.perf-dwarf-fallback") + "\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            // DWARF unwinding copies 16 KiB of stack per sample, hence the lower rate; inlined frames come out of it, so inlining stays on
            profile = record(console, target, tmp.resolve("perf-dwarf.data"), listOf("--call-graph", "dwarf,16384", "-F", "499"), noInline = false, comm = comm, show = runs <= 1)
        }
        if (runs > 1)
            console.print(ProfilerBundle.message("console.repeated", runs, profile.samples) + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        return Recording(profile, tmp)
    }

    /**
     * Wraps [program] in [repeatLoopScript]: perf's default `--inherit` follows every forked child, so one `perf record`
     * around the loop collects all of their samples together. The loop sizes itself off the wall clock rather than a
     * calibration run's timing, whose own `perf record`/`perf script` overhead (100s of ms) would dwarf a sub-millisecond
     * program and starve the loop of iterations; [countFile] is how it reports back how many it ran.
     */
    private fun loopWrap(program: GeneralCommandLine, countFile: Path): GeneralCommandLine =
        GeneralCommandLine(listOf("sh", "-c", repeatLoopScript(countFile)) + program.getCommandLineList(null))
            .withWorkDirectory(program.workDirectory)
            .withEnvironment(program.environment)
            .withParentEnvironmentType(program.parentEnvironmentType)

    private fun readRunCount(countFile: Path): Int = runCatching { countFile.readText().trim().toInt() }.getOrDefault(1)

    /** Linux truncates a process's comm (as `perf script --comms` matches it) to 15 visible characters. */
    private fun commOf(program: GeneralCommandLine): String = Path.of(program.exePath).fileName.toString().take(15)

    private suspend fun record(console: ConsoleView, program: GeneralCommandLine, data: Path, options: List<String>, noInline: Boolean, comm: String? = null, show: Boolean = true): CpuProfile {
        val recorded = console.run(program.wrap("perf", "record", *options.toTypedArray(), "-o", data.toString(), "--"), show = show)
        if (!data.exists() || data.fileSize() == 0L) {
            if ("perf_event_paranoid" in recorded.output)
                throw ExecutionException(ProfilerBundle.message("error.perf-paranoid"))
            throw ExecutionException(ProfilerBundle.message("error.no-perf-data"))
        }
        var scripted = script(console, data, noInline, comm)
        if (!noInline && ADDR2LINE_FAILED in scripted.output) {
            // perf 6.x exits 0 and prints garbage without binutils; the inlined frames are lost, the stacks are not
            console.print(ProfilerBundle.message("console.perf-no-addr2line") + "\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            scripted = script(console, data, noInline = true, comm)
        }
        if (scripted.exit != 0 || ADDR2LINE_FAILED in scripted.output)
            throw ExecutionException(ProfilerBundle.message("error.perf-script-failed", scripted.stderr.trim().ifEmpty { scripted.stdout.firstLines() }))
        // the whole perf script text sits in memory, about 100 bytes per frame; streaming it would need a line-wise process listener
        val profile = PerfScript.parse(scripted.stdout)
        if (profile.samples == 0 && scripted.stdout.isNotBlank())
            throw ExecutionException(ProfilerBundle.message("error.perf-script-unparsed", scripted.stdout.firstLines()))
        return profile
    }

    /** perf script output is data, not a log: captured, not shown in the console. [comm], when given, keeps only samples of that process name (drops the repeat loop's own `sh` samples). */
    private suspend fun script(console: ConsoleView, data: Path, noInline: Boolean, comm: String? = null): StepResult {
        val cli = GeneralCommandLine("perf", "script", "-i", data.toString()).withWorkingDirectory(data.parent)
        if (noInline) cli.addParameter("--no-inline")
        if (comm != null) cli.addParameters("--comms", comm)
        return console.run(cli, show = false)
    }
}

/**
 * A `sh -c` script that runs `"$0" "$@"` (the program perf wraps it around) in a loop, timing itself against the wall
 * clock rather than a fixed count: [RepeatStrategy.TARGET_NANOS] of wall time or [RepeatStrategy.MAX_RUNS] iterations,
 * whichever comes first, the same budget [RepeatStrategy.shouldRunAgain] uses for xctrace's iterative loop. It writes
 * how many iterations it actually ran to [countFile], the only way the caller learns that back.
 */
internal fun repeatLoopScript(countFile: Path): String =
    "i=0; end=\$(( \$(date +%s%N) + ${RepeatStrategy.TARGET_NANOS} )); " +
        "while [ \$i -lt ${RepeatStrategy.MAX_RUNS} ] && [ \$(date +%s%N) -lt \$end ]; do \"\$0\" \"\$@\"; i=\$((i+1)); done; " +
        "echo \$i > '$countFile'"

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
 * Runs one step, echoing its command line to the console unless [quiet] (a repeated calibration/loop run would
 * otherwise flood it); with [show] the process output streams into the console as well (off for output that is
 * data). Cancelling destroys the process (a soft kill first, which lets xctrace save).
 */
internal suspend fun ConsoleView.run(cli: GeneralCommandLine, show: Boolean = true, quiet: Boolean = false): StepResult {
    if (!quiet) print("${cli.commandLineString}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
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

/** A [ConsoleView.startBackground] process plus [ready], which completes once its output contains the caller's marker string. */
internal class BackgroundProcess(val handler: KillableColoredProcessHandler, val ready: CompletableDeferred<Unit>)

/**
 * Starts [cli] without waiting for it to exit, for a recorder that must outlive several profiled runs; stop it with
 * [stopBackground]. [readyMarker] appearing in its output completes [BackgroundProcess.ready]; exiting before that
 * completes it exceptionally with its captured output, for [awaitReady] to report instead of hanging.
 */
internal fun ConsoleView.startBackground(cli: GeneralCommandLine, readyMarker: String): BackgroundProcess {
    print("${cli.commandLineString}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    val handler = KillableColoredProcessHandler(cli)
    val output = StringBuilder()
    val ready = CompletableDeferred<Unit>()
    handler.addProcessListener(object : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            val text = synchronized(output) { output.append(event.text).toString() }
            if (readyMarker in text) ready.complete(Unit)
        }

        override fun processTerminated(event: ProcessEvent) {
            if (!ready.isCompleted)
                ready.completeExceptionally(ExecutionException(ProfilerBundle.message("error.recorder-exited", synchronized(output) { output.toString() }.firstLines())))
        }
    })
    handler.startNotify()
    return BackgroundProcess(handler, ready)
}

/** Waits up to [timeoutMillis] for [process] to reach its ready marker; throws (the recorder's own message) if it exited first, or a timeout message if it never got there. */
internal suspend fun awaitReady(process: BackgroundProcess, timeoutMillis: Long) {
    val reached = withTimeoutOrNull(timeoutMillis) {
        process.ready.await()
        true
    }
    if (reached != true)
        throw ExecutionException(ProfilerBundle.message("error.recorder-timeout"))
}

/** Soft-kills a [ConsoleView.startBackground] handler; escalates to a hard kill if it has not exited after [timeoutMillis] (xctrace's soft stop measured up to ~9.5s). */
internal suspend fun ConsoleView.stopBackground(handler: KillableColoredProcessHandler, timeoutMillis: Long = 20_000L) {
    handler.destroyProcess()
    withContext(Dispatchers.IO + NonCancellable) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!handler.waitFor(100)) {
            if (System.nanoTime() >= deadline) {
                handler.killProcess()
                handler.waitFor()
                break
            }
        }
    }
}

private fun String.firstLines() = lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n").trim()
