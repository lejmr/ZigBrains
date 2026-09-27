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

package com.falsepattern.zigbrains.project.execution.build

import com.falsepattern.zigbrains.project.execution.test.ZigTestOutputParser
import com.falsepattern.zigbrains.project.execution.test.ZigTestProcessHandler
import com.falsepattern.zigbrains.project.run.ZigProcessHandler
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import java.io.OutputStream
import java.nio.file.Path

/**
 * Runs `zig build <steps> --verbose` and then, through a [ZigTestProcessHandler], every `zig test` compile
 * the build runner printed ([discover] turns the build's `--listen=-` lines into those commands), forwarding
 * both phases' output to this handler's listeners. The build's own output sits under a suite for [buildFile];
 * that suite gets the build's exit code when no test compile was found (a broken build is a red leaf rather
 * than an empty tree) and again after the re-runs when the build failed but every re-run passed (a failure
 * in a non-test step would otherwise be hidden). A Stop paints no red leaf: the markers are skipped once
 * [stopped]. [build] must merge stderr into stdout: lines are reassembled from one stream.
 */
class ZigBuildTestProcessHandler(
    private val build: GeneralCommandLine,
    private val buildFile: Path,
    private val banner: String? = null,
    private val discover: (List<String>) -> List<ZigTestProcessHandler.Command>,
) : ProcessHandler() {
    @Volatile
    private var stopped = false
    @Volatile
    private var current: ProcessHandler? = null
    private val listenLines = ArrayList<String>()
    private val partial = StringBuilder()

    override fun startNotify() {
        super.startNotify()
        // A SYSTEM line that is not a marker shows up as plain (recorded) console output.
        banner?.let { notifyTextAvailable(it + "\n", ProcessOutputTypes.SYSTEM) }
        buildFileMarker()
        val handler = try {
            ZigProcessHandler(build)
        } catch (e: ExecutionException) {
            notifyTextAvailable((e.message ?: e.toString()) + "\n", ProcessOutputTypes.STDERR)
            exitMarker(1)
            notifyProcessTerminated(1)
            return
        }
        current = handler
        if (stopped) handler.destroyProcess()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                notifyTextAvailable(event.text, outputType)
                collect(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                current = null
                if (partial.isNotEmpty()) {
                    notifyTextAvailable("\n", ProcessOutputTypes.STDOUT)
                    line(partial.toString())
                    partial.setLength(0)
                }
                runTests(event.exitCode)
            }
        })
        handler.startNotify()
    }

    // Output arrives in arbitrary chunks; the discovery wants whole lines.
    private fun collect(text: String) {
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) {
                partial.append(text, start, text.length)
                return
            }
            partial.append(text, start, newline)
            line(partial.toString())
            partial.setLength(0)
            start = newline + 1
        }
    }

    // Only the spawned-command lines matter; compiler output and summaries are not kept.
    private fun line(text: String) {
        if (text.contains(ZigBuildTestDiscovery.LISTEN)) listenLines += text
    }

    private fun runTests(buildExitCode: Int) {
        val commands = if (stopped) emptyList() else discover(listenLines)
        if (commands.isEmpty()) {
            exitMarker(buildExitCode)
            notifyProcessTerminated(buildExitCode)
            return
        }
        val tests = ZigTestProcessHandler(commands)
        current = tests
        if (stopped) tests.destroyProcess()
        tests.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                notifyTextAvailable(event.text, outputType)
            }

            override fun processTerminated(event: ProcessEvent) {
                current = null
                if (buildExitCode != 0 && event.exitCode == 0 && !stopped) {
                    buildFileMarker()
                    notifyTextAvailable("zig build exited with $buildExitCode although every test passed; see the build output above\n", ProcessOutputTypes.STDERR)
                    exitMarker(buildExitCode)
                }
                // A failing build already failed the run; otherwise the test binaries' verdict is the run's.
                notifyProcessTerminated(if (buildExitCode != 0) buildExitCode else event.exitCode)
            }
        })
        tests.startNotify()
    }

    private fun buildFileMarker() {
        notifyTextAvailable(ZigTestOutputParser.FILE_MARKER + buildFile.toAbsolutePath() + "\n", ProcessOutputTypes.SYSTEM)
    }

    private fun exitMarker(exitCode: Int) {
        if (stopped) return
        notifyTextAvailable(ZigTestOutputParser.EXIT_MARKER + exitCode + "\n", ProcessOutputTypes.SYSTEM)
    }

    override fun destroyProcessImpl() {
        stopped = true
        val child = current
        if (child != null) {
            child.destroyProcess()
        } else {
            notifyProcessTerminated(1)
        }
    }

    override fun detachProcessImpl() {
        stopped = true
        current?.detachProcess()
        current = null
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = current?.processInput
}
