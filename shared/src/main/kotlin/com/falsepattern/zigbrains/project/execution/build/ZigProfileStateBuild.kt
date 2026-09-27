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
import com.falsepattern.zigbrains.project.execution.base.ZigProfileState
import com.falsepattern.zigbrains.project.execution.test.ZigTestConsoleProperties
import com.falsepattern.zigbrains.project.execution.test.ZigTestPackages
import com.falsepattern.zigbrains.project.execution.test.ZigTestProcessHandler
import com.falsepattern.zigbrains.project.toolchain.ZigToolchainService
import com.falsepattern.zigbrains.shared.cli.startIPCAwareProcess
import com.falsepattern.zigbrains.shared.coroutine.runModalOrBlocking
import com.falsepattern.zigbrains.shared.coroutine.withEDTContext
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.openapi.application.ModalityState
import com.intellij.platform.ide.progress.ModalTaskOwner
import java.nio.file.Path

class ZigProfileStateBuild(environment: ExecutionEnvironment, configuration: ZigExecConfigBuild) : ZigProfileState<ZigExecConfigBuild>(environment, configuration) {
    /** A plain Run of a test step gets a test tree (see [ZigBuildTestDiscovery]); a fuzz build gets a much
     *  thinner one (see [ZigFuzzConsoleProperties]); Debug and other steps are untouched. */
    @Throws(ExecutionException::class)
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        // Steps and extra args together: `--fuzz`/`--webui`/`--watch` may sit in either field.
        val args = configuration.buildSteps.argsSplit() + configuration.extraArgs.argsSplit()
        if (executor.id != DefaultRunExecutor.EXECUTOR_ID) {
            return super.execute(executor, runner)
        }
        if (isFuzzBuild(args)) {
            return executeFuzz(executor)
        }
        if (!ZigBuildTestDiscovery.runsTests(args)) {
            return super.execute(executor, runner)
        }
        return runModalOrBlocking({ ModalTaskOwner.project(environment.project) }, { "ZigProfileStateBuild.execute" }) {
            val toolchain = ZigToolchainService.getInstance(environment.project).toolchain
                ?: throw ExecutionException(ZigBrainsBundle.message("exception.zig-profile-state.start-process.no-toolchain"))
            // --verbose goes right after `build`: anything after a `--` in the extra args belongs to the run steps.
            val args = configuration.buildCommandLineArgs(false)
            val build = createCommandLine(toolchain, GeneralCommandLine().withRedirectErrorStream(true), listOf(args[0], "--verbose") + args.drop(1))
            // The compile lines are relative to the build's working directory (`--cache-dir .zig-cache`), so the
            // re-runs get the same one; a missing one means "wherever the IDE runs", for both.
            val workDir = configuration.workingDirectory.path
            // zig build itself looks upward for build.zig, so the suite does too (a working directory inside the package).
            val base = workDir ?: Path.of("").toAbsolutePath()
            val buildFile = (ZigTestPackages.nearestPackageDir(base) ?: base).resolve("build.zig")
            // console.print on an SMTRunnerConsoleView isn't recorded on any test node (see
            // ZigProfileStateTest), so this goes through the handler as a recorded SYSTEM line instead.
            val handler = ZigBuildTestProcessHandler(build, buildFile, workingDirectoryLine()) { lines ->
                ZigBuildTestDiscovery.testCompiles(lines).map { compile ->
                    val cli = GeneralCommandLine(compile.argv).withWorkingDirectory(workDir).withRedirectErrorStream(true).withCharset(Charsets.UTF_8)
                    cli.environment.putAll(build.environment)
                    val file = compile.rootFile?.let { workDir?.resolve(it) ?: Path.of(it) } ?: buildFile
                    ZigTestProcessHandler.Command(file, null, cli)
                }
            }
            ProcessTerminatedListener.attach(handler)
            val console = withEDTContext(ModalityState.any()) {
                SMTestRunnerConnectionUtil.createAndAttachConsole(
                    ZigTestConsoleProperties.FRAMEWORK_NAME,
                    handler,
                    ZigTestConsoleProperties(configuration, executor)
                )
            }
            DefaultExecutionResult(console, handler)
        }
    }

    // A fuzz build is one long-running process with no discrete test list to discover ahead of time (unlike
    // ZigBuildTestDiscovery's per-file compile lines), so it keeps the plain single-process command line and
    // only swaps the console: raw output still streams through unchanged, but ZigFuzzEventsConverter also turns
    // every crash line into a failed node under a "Fuzzing" suite instead of only the balloon
    // (ZigFuzzCrashExecutionListener, attached separately and unaffected by this).
    // ponytail: no workingDirectoryLine() banner here (unlike the test-tree path above) - it would need its own
    // banner-aware process handler for one log line; add if that line turns out to matter in practice.
    private fun executeFuzz(executor: Executor): ExecutionResult {
        return runModalOrBlocking({ ModalTaskOwner.project(environment.project) }, { "ZigProfileStateBuild.executeFuzz" }) {
            val toolchain = ZigToolchainService.getInstance(environment.project).toolchain
                ?: throw ExecutionException(ZigBrainsBundle.message("exception.zig-profile-state.start-process.no-toolchain"))
            val handler = getCommandLine(toolchain, false).startIPCAwareProcess(environment.project, emulateTerminal = true)
            val console = withEDTContext(ModalityState.any()) {
                SMTestRunnerConnectionUtil.createAndAttachConsole(
                    ZigTestConsoleProperties.FRAMEWORK_NAME,
                    handler,
                    ZigFuzzConsoleProperties(configuration, executor)
                )
            }
            DefaultExecutionResult(console, handler)
        }
    }
}
