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
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Key
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

/** A crash reported by `zig build ... --fuzz`: the test, how its process ended, and the saved input as zig printed it. */
data class ZigFuzzCrash(val test: String, val term: String, val input: String)

// Zig 0.16 (lib/std/Build/Step/Run.zig, FuzzTestRunner.saveCrash) copies the crashing instance's input to
// `<cache_root>f/crash` and fails the step with exactly this line; the process keeps running (web server).
// There is one `f/crash` per cache: the next crash, in this run or a later one, overwrites it, so the balloon
// and the replay command are only right until then; copy the file away if it matters.
private val CRASH_LINE = Regex("""^error: test '(.+)' ((?:terminated|stopped) with signal \S+|exited with code \d+|terminated for unknown reason with code \d+); input saved to '(.+)'$""")
private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
private const val MAX_BUFFER = 4096

/** Parses one output line (ANSI already stripped) into a [ZigFuzzCrash], or null for any other line. */
fun parseFuzzCrash(line: String): ZigFuzzCrash? {
    // a progress spinner may have been overwritten in place, only what follows the last carriage return is the line
    val m = CRASH_LINE.matchEntire(line.trimEnd('\r').substringAfterLast('\r')) ?: return null
    return ZigFuzzCrash(m.groupValues[1], m.groupValues[2], m.groupValues[3])
}

/** The `zig build` arguments before `--`; what follows belongs to the built program. */
private fun buildArgs(args: List<String>): List<String> = args.takeWhile { it != "--" }

/** True when the `zig build` arguments ask for fuzzing (`--fuzz` or `--fuzz=<limit>`). */
fun isFuzzBuild(args: List<String>): Boolean = buildArgs(args).any { it.startsWith("--fuzz") }

/** Single-quotes [s] for POSIX shells. */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

// Zig 0.16 has no flag to replay `f/crash`: plain `zig build test` runs only the `.corpus` entries and the fuzzer
// does not re-read the crash file. The file is in the `std.testing.Smith` corpus encoding, so copying it next to
// the test and listing it in `.corpus` reproduces the crash deterministically; that is the only replay there is.
// The crash line names the test (`main.test.fuzz`), not its file, so the copy lands in the cwd.
/** The shell line that reproduces [crash]: copies [input] to `fuzz-crash` in the cwd, then runs the build without `--fuzz`/`--webui`. */
fun replayCommand(crash: ZigFuzzCrash, input: Path, args: List<String>): String {
    val kept = buildArgs(args).filterNot { it.startsWith("--fuzz") || it.startsWith("--webui") }
    return "cp ${shellQuote(input.toString())} fuzz-crash && zig build ${kept.joinToString(" ") { shellQuote(it) }}" +
        "   # move fuzz-crash next to the test file and add `.corpus = &.{ @embedFile(\"fuzz-crash\") }` to the std.testing.fuzz options"
}

/** Reassembles PTY chunks into lines, strips ANSI colors and returns the crash lines completed by each chunk. */
class ZigFuzzCrashDetector {
    private val buffer = StringBuilder()

    fun feed(text: String): List<ZigFuzzCrash> {
        buffer.append(text)
        val found = ArrayList<ZigFuzzCrash>(0)
        var newline = buffer.indexOf("\n")
        while (newline >= 0) {
            val line = ANSI_ESCAPE.replace(buffer.substring(0, newline), "")
            buffer.delete(0, newline + 1)
            parseFuzzCrash(line)?.let(found::add)
            newline = buffer.indexOf("\n")
        }
        if (buffer.length > MAX_BUFFER)
            buffer.clear()
        return found
    }
}

/** Attaches [ZigFuzzCrashProcessListener] to Zig build runs whose steps contain `--fuzz`. */
class ZigFuzzCrashExecutionListener : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (executorId == DefaultDebugExecutor.EXECUTOR_ID)
            return // the debugger builds the debug steps and runs the binary, nothing fuzzes
        val config = env.runProfile as? ZigExecConfigBuild ?: return
        val args = config.buildSteps.argsSplit() + config.extraArgs.argsSplit()
        if (!isFuzzBuild(args))
            return
        val workDir = config.workingDirectory.path ?: env.project.guessProjectDir()?.toNioPath() ?: return
        handler.addProcessListener(ZigFuzzCrashProcessListener(env.project, workDir, args))
    }
}

/** On a crash line: echoes it to the console and shows a balloon with "Show input" and "Copy replay command". */
class ZigFuzzCrashProcessListener(
    private val project: Project,
    private val workDir: Path,
    private val args: List<String>,
) : ProcessListener {
    private val detector = ZigFuzzCrashDetector()

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        if (outputType == ProcessOutputTypes.SYSTEM)
            return // our own echo and the platform's exit line
        for (crash in detector.feed(event.text))
            report(crash, event.processHandler)
    }

    private fun report(crash: ZigFuzzCrash, handler: ProcessHandler) {
        val input = workDir.resolve(crash.input).toAbsolutePath().normalize()
        val title = ZigBrainsBundle.message("notification.title.fuzz-crash")
        val content = ZigBrainsBundle.message("notification.content.fuzz-crash", crash.test, crash.term, input)
        handler.notifyTextAvailable("$title: $content\n", ProcessOutputTypes.SYSTEM)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("zigbrains")
            .createNotification(title, content, NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring(ZigBrainsBundle.message("notification.content.fuzz-crash.show")) {
                // the input is binary and has no extension, so reveal it in the file manager instead of the editor
                RevealFileAction.openFile(input)
            })
            .addAction(NotificationAction.createSimpleExpiring(ZigBrainsBundle.message("notification.content.fuzz-crash.copy")) {
                CopyPasteManager.getInstance().setContents(StringSelection(replayCommand(crash, input, args)))
            })
            .notify(project)
    }
}
