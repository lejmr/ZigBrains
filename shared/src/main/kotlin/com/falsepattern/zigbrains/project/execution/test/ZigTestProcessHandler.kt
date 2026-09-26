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
import java.util.Collections

/**
 * Runs a `zig test` invocation per file, one child process at a time, forwarding all their output into
 * this handler's own listeners. A [ZigTestOutputParser.FILE_MARKER] line precedes each file and a
 * [ZigTestOutputParser.EXIT_MARKER] line follows it, so the parser can tell files apart and detect a
 * child that produced no test output at all (e.g. a compile error).
 */
class ZigTestProcessHandler(private val commands: List<Pair<Path, GeneralCommandLine>>) : ProcessHandler() {
    // destroy/detach run on the EDT while runNext's continuation runs on a child's pooled termination
    // thread; `stopped` is the flag both sides check/set so a Stop landing between `current = null` and
    // `current = handler` still kills the child that gets spawned.
    @Volatile
    private var stopped = false
    private var nextIndex = 0
    @Volatile
    private var current: ZigProcessHandler? = null
    @Volatile
    private var worstExitCode = 0
    private val streamsPendingNewline = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Key<*>, Boolean>())

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
        notifyTextAvailable(ZigTestOutputParser.FILE_MARKER + path.toAbsolutePath() + "\n", ProcessOutputTypes.SYSTEM)
        val handler = try {
            ZigProcessHandler(commandLine)
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
                if (event.text.isNotEmpty()) {
                    if (event.text.endsWith("\n")) streamsPendingNewline.remove(outputType) else streamsPendingNewline.add(outputType)
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                if (worstExitCode == 0 && event.exitCode != 0) {
                    worstExitCode = event.exitCode
                }
                // A test that calls std.process.exit() (or a crash) can leave the last line without a
                // trailing newline; without this, it would glue onto the next file's first line.
                for (key in streamsPendingNewline) notifyTextAvailable("\n", key)
                streamsPendingNewline.clear()
                notifyTextAvailable(ZigTestOutputParser.EXIT_MARKER + event.exitCode + "\n", ProcessOutputTypes.SYSTEM)
                current = null
                runNext()
            }
        })
        handler.startNotify()
    }

    override fun destroyProcessImpl() {
        stopped = true
        val child = current
        if (child != null) {
            child.destroyProcess()
        } else {
            notifyProcessTerminated(worstExitCode)
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
