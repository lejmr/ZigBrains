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

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import java.io.OutputStream
import java.nio.file.Path

/**
 * Runs a `zig test` invocation per file, one child process at a time, forwarding all their
 * output into this handler's own listeners (and thus into the SMTRunner test console).
 * A [ZigTestEventsConverter.FILE_MARKER] line is emitted before each file so the output parser
 * can tell where one file's test run ends and the next one's begins.
 */
class ZigTestProcessHandler(private val commands: List<Pair<Path, GeneralCommandLine>>) : ProcessHandler() {
    // ponytail: destroy/detach run on the EDT while runNext's continuation runs on a child's
    // pooled termination thread; `stopped` is the single flag both sides check/set so a Stop
    // that lands in the gap between `current = null` and `current = handler` still kills the
    // child that gets spawned, instead of leaking an untracked, unkillable process.
    @Volatile
    private var stopped = false
    @Volatile
    private var nextIndex = 0
    @Volatile
    private var current: OSProcessHandler? = null
    @Volatile
    private var worstExitCode = 0

    override fun startNotify() {
        super.startNotify()
        runNext()
    }

    private fun runNext() {
        if (stopped || nextIndex >= commands.size) {
            notifyProcessTerminated(worstExitCode)
            return
        }
        val (path, commandLine) = commands[nextIndex]
        nextIndex++
        notifyTextAvailable(ZigTestEventsConverter.FILE_MARKER + path.toAbsolutePath() + "\n", ProcessOutputTypes.SYSTEM)
        val handler = try {
            OSProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            notifyTextAvailable((e.message ?: e.toString()) + "\n", ProcessOutputTypes.STDERR)
            if (worstExitCode == 0) worstExitCode = 1
            runNext()
            return
        }
        current = handler
        if (stopped) handler.destroyProcess()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                notifyTextAvailable(event.text, outputType)
            }

            override fun processTerminated(event: ProcessEvent) {
                if (worstExitCode == 0 && event.exitCode != 0) {
                    worstExitCode = event.exitCode
                }
                current = null
                runNext()
            }
        })
        handler.startNotify()
    }

    override fun destroyProcessImpl() {
        stopped = true
        nextIndex = commands.size
        val child = current
        if (child != null) {
            child.destroyProcess()
        } else {
            notifyProcessTerminated(worstExitCode)
        }
    }

    override fun detachProcessImpl() {
        stopped = true
        nextIndex = commands.size
        current?.detachProcess()
        current = null
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = current?.processInput
}
