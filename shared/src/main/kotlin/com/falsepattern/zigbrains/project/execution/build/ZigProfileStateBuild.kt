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
    /** A plain Run of a test step gets a test tree (see [ZigBuildTestDiscovery]); Debug and other steps are untouched. */
    @Throws(ExecutionException::class)
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        // Steps and extra args together: `--fuzz`/`--webui`/`--watch` may sit in either field.
        if (executor.id != DefaultRunExecutor.EXECUTOR_ID || !ZigBuildTestDiscovery.runsTests(configuration.buildSteps.argsSplit() + configuration.extraArgs.argsSplit())) {
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
            val handler = ZigBuildTestProcessHandler(build, buildFile) { lines ->
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
}
