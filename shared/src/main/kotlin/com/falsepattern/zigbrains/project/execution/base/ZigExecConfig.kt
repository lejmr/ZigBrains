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

package com.falsepattern.zigbrains.project.execution.base

import com.falsepattern.zigbrains.direnv.DirenvService
import com.falsepattern.zigbrains.project.execution.test.ZigExecConfigTest.Companion.kcovArgs
import com.falsepattern.zigbrains.ZigBrainsBundle
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.NlsActions.ActionText
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.util.EnvironmentUtil
import org.jdom.Element
import org.jetbrains.annotations.Nls
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isExecutable

abstract class ZigExecConfig<T: ZigExecConfig<T>>(project: Project, factory: ConfigurationFactory, @Nls name: String): LocatableConfigurationBase<ZigProfileState<T>>(project, factory, name) {
    var workingDirectory = WorkDirectoryConfigurable("workingDirectory").apply { path = project.guessProjectDir()?.toNioPathOrNull() }
        private set
	var environment = EnvConfigurable(project)
		private set

	abstract val suggestedName: @ActionText String
    @Throws(ExecutionException::class)
    abstract suspend fun buildCommandLineArgs(debug: Boolean): List<String>
    abstract override fun getState(executor: Executor, environment: ExecutionEnvironment): ZigProfileState<T>

    /** Where a coverage run of this configuration writes kcov's output, or null without a project dir. */
    fun coverageDir(): Path? {
        val base = project.basePath ?: return null
        return Path(base, ".zig-cache", "zigbrains-coverage", name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    }

    /** Clears [coverageDir] and returns the `zig test` args that run each test binary under kcov into it. */
    @Throws(ExecutionException::class)
    fun prepareCoverageArgs(): List<String> {
        // The shell PATH (EnvironmentUtil loads it even for an IDE started from the Dock), then the Homebrew locations.
        // kcov is Linux/macOS only, so a plain scan does; PathEnvironmentVariableUtil.findInPath is deprecated in 2026.3.
        val dirs = EnvironmentUtil.getValue("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() } + listOf("/opt/homebrew/bin", "/usr/local/bin")
        val kcov = dirs.map { Path(it, "kcov") }.firstOrNull { it.isExecutable() }
            ?: throw ExecutionException(ZigBrainsBundle.message("exception.zig.kcov-not-found"))
        val outDir = coverageDir() ?: throw ExecutionException(ZigBrainsBundle.message("exception.zig.coverage-no-project-dir"))
        try {
            NioFiles.deleteRecursively(outDir)
            Files.createDirectories(outDir)
        } catch (e: IOException) {
            throw ExecutionException(e)
        }
        return kcovArgs(kcov.toString(), project.basePath!!, outDir.toString())
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return ZigConfigEditor(this)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        getConfigurables().forEach { it.readExternal(element) }
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        getConfigurables().forEach { it.writeExternal(element) }
    }


    suspend fun patchCommandLine(commandLine: GeneralCommandLine): GeneralCommandLine {
        val direnv = DirenvService.getInstance(project)
        if (direnv.isEnabled.isEnabled(project)) {
            commandLine.withEnvironment(direnv.import().env)
        }
		commandLine.withEnvironment(environment.variables.envs)
        return commandLine
    }

    override fun clone(): T {
        val myClone = super.clone() as ZigExecConfig<*>
        myClone.workingDirectory = workingDirectory.clone()
        myClone.environment = environment.clone()
        @Suppress("UNCHECKED_CAST")
        return myClone as T
    }

    open fun getConfigurables(): List<ZigConfigurable<*>> = listOf(workingDirectory, environment)
}