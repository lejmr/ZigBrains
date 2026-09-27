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

import com.falsepattern.zigbrains.ZigBrainsBundle
import com.intellij.execution.ExecutionListener
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

/** A crash reported by `zig build ... --fuzz`: the test, how its process ended, and the saved input, relative to the build's cwd. */
data class ZigFuzzCrash(val test: String, val term: String, val input: String)

// Zig 0.16 (lib/std/Build/Step/Run.zig, FuzzTestRunner.saveCrash) copies the crashing instance's input to
// `<cache_root>f/crash` and fails the step with exactly this line; the process keeps running (web server).
private val CRASH_LINE = Regex("""^error: test '(.+)' ((?:terminated|stopped) with signal \S+|exited with code \d+|terminated for unknown reason with code \d+); input saved to '(.+)'$""")
private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
private const val MAX_BUFFER = 4096

/** Parses one output line (ANSI already stripped) into a [ZigFuzzCrash], or null for any other line. */
fun parseFuzzCrash(line: String): ZigFuzzCrash? {
    val m = CRASH_LINE.matchEntire(line.trimEnd('\r')) ?: return null
    return ZigFuzzCrash(m.groupValues[1], m.groupValues[2], m.groupValues[3])
}

/** True when the `zig build` arguments ask for fuzzing (`--fuzz` or `--fuzz=<limit>`). */
fun isFuzzBuild(args: List<String>): Boolean = args.any { it.startsWith("--fuzz") }

// Zig 0.16 has no flag to replay `f/crash`: plain `zig build test` runs only the `.corpus` entries and the fuzzer
// does not re-read the crash file. The file is in the `std.testing.Smith` corpus encoding, so copying it next to
// the test and listing it in `.corpus` reproduces the crash deterministically; that is the only replay there is.
/** The shell line that reproduces [crash] once the test lists the copied file in `.corpus`; `--fuzz`/`--webui` are dropped. */
fun replayCommand(crash: ZigFuzzCrash, buildArgs: List<String>): String {
    val args = buildArgs.filterNot { it.startsWith("--fuzz") || it.startsWith("--webui") }
    return "cp '${crash.input}' src/fuzz-crash && zig build ${args.joinToString(" ")}" +
        "   # first add `.corpus = &.{ @embedFile(\"fuzz-crash\") }` to the std.testing.fuzz options"
}

/** Reassembles PTY chunks into lines, strips ANSI colors and reports every crash line to [onCrash]. */
class ZigFuzzCrashDetector(private val onCrash: (ZigFuzzCrash) -> Unit) {
    private val buffer = StringBuilder()

    fun feed(text: String) {
        buffer.append(text)
        var newline = buffer.indexOf("\n")
        while (newline >= 0) {
            val line = ANSI_ESCAPE.replace(buffer.substring(0, newline), "")
            buffer.delete(0, newline + 1)
            parseFuzzCrash(line)?.let(onCrash)
            newline = buffer.indexOf("\n")
        }
        if (buffer.length > MAX_BUFFER)
            buffer.clear()
    }
}

/** Attaches [ZigFuzzCrashProcessListener] to Zig build runs whose steps contain `--fuzz`. */
class ZigFuzzCrashExecutionListener : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val config = env.runProfile as? ZigExecConfigBuild ?: return
        val debug = executorId == DefaultDebugExecutor.EXECUTOR_ID
        val args = (if (debug) config.debugBuildSteps else config.buildSteps).argsSplit() +
            (if (debug) config.debugExtraArgs else config.extraArgs).argsSplit()
        if (!isFuzzBuild(args))
            return
        val workDir = config.workingDirectory.path ?: env.project.guessProjectDir()?.toNioPath() ?: return
        handler.addProcessListener(ZigFuzzCrashProcessListener(env.project, workDir, args))
    }
}

/** On a crash line: echoes it to the console and shows a balloon with "Open input" and "Copy replay command". */
class ZigFuzzCrashProcessListener(
    private val project: Project,
    private val workDir: Path,
    private val buildArgs: List<String>,
) : ProcessListener {
    private var handler: ProcessHandler? = null
    private val detector = ZigFuzzCrashDetector(::report)

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        if (outputType == ProcessOutputTypes.SYSTEM)
            return // our own echo and the platform's exit line
        handler = event.processHandler
        detector.feed(event.text)
    }

    private fun report(crash: ZigFuzzCrash) {
        val input = workDir.resolve(crash.input)
        val title = ZigBrainsBundle.message("notification.title.fuzz-crash")
        val content = ZigBrainsBundle.message("notification.content.fuzz-crash", crash.test, crash.term, input)
        handler?.notifyTextAvailable("$title: $content\n", ProcessOutputTypes.SYSTEM)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("zigbrains")
            .createNotification(title, content, NotificationType.WARNING)
            .addAction(NotificationAction.createSimple(ZigBrainsBundle.message("notification.content.fuzz-crash.open")) {
                // ponytail: silently a no-op when zig already replaced the file; the balloon text still names the path
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(input) ?: return@createSimple
                OpenFileDescriptor(project, file).navigate(true)
            })
            .addAction(NotificationAction.createSimple(ZigBrainsBundle.message("notification.content.fuzz-crash.copy")) {
                CopyPasteManager.getInstance().setContents(StringSelection(replayCommand(crash, buildArgs)))
            })
            .notify(project)
    }
}
