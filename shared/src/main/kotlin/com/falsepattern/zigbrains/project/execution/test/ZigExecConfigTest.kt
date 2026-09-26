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

import com.falsepattern.zigbrains.ZigBrainsBundle
import com.falsepattern.zigbrains.project.execution.base.ArgsConfigurable
import com.falsepattern.zigbrains.project.execution.base.FilePathConfigurable
import com.falsepattern.zigbrains.project.execution.base.OptimizationConfigurable
import com.falsepattern.zigbrains.project.execution.base.ZigConfigurable
import com.falsepattern.zigbrains.project.execution.base.ZigExecConfig
import com.falsepattern.zigbrains.project.execution.base.ZigProfileState
import com.falsepattern.zigbrains.shared.sanitizedPathString
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.isDirectory

class ZigExecConfigTest(project: Project, factory: ConfigurationFactory): ZigExecConfig<ZigExecConfigTest>(project, factory, ZigBrainsBundle.message("exec.type.test.label")) {
    var filePath = FilePathConfigurable("filePath", ZigBrainsBundle.message("exec.option.label.file-path"))
        private set
    var optimization = OptimizationConfigurable("optimization")
        private set
    var compilerArgs = ArgsConfigurable("compilerArgs", ZigBrainsBundle.message("exec.option.label.compiler-args"), true)
        private set

    /**
     * The individual `.zig` files a single run of this configuration should invoke `zig test` on.
     * A file path resolves to itself; a directory path resolves to every `.zig` file recursively
     * under it that contains a top-level `test` declaration (skipping zig-cache/zig-out directories).
     */
    @Throws(ExecutionException::class)
    fun testFiles(): List<Path> {
        val path = filePath.path ?: throw ExecutionException(ZigBrainsBundle.message("exception.zig.empty-file-path"))
        if (!path.isDirectory()) {
            return listOf(path)
        }
        val excludedDirs = setOf("zig-cache", ".zig-cache", "zig-out")
        val hasTestDecl = Regex("(?m)^\\s*test\\b")
        return Files.walk(path).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".zig") }
                  .filter { file -> path.relativize(file).none { part -> part.toString() in excludedDirs } }
                  .filter { hasTestDecl.containsMatchIn(Files.readString(it)) }
                  .sorted()
                  .collect(Collectors.toList())
        }
    }

    @Throws(ExecutionException::class)
    suspend fun buildCommandLineArgs(debug: Boolean, file: Path): List<String> {
        val result = ArrayList<String>()
        result.add("test")
        result.add(file.sanitizedPathString ?: throw ExecutionException(ZigBrainsBundle.message("exception.zig.empty-file-path")))
        if (!debug || optimization.forced) {
            result.addAll(listOf("-O", optimization.level.name))
        }
        result.addAll(compilerArgs.argsSplit())
        if (debug) {
            result.add("--test-no-exec")
        }
        return result
    }

    @Throws(ExecutionException::class)
    override suspend fun buildCommandLineArgs(debug: Boolean): List<String> {
        return buildCommandLineArgs(debug, filePath.path ?: throw ExecutionException(ZigBrainsBundle.message("exception.zig.empty-file-path")))
    }

    override val suggestedName: String
        get() = ZigBrainsBundle.message("configuration.test.suggested-name")

    override fun clone(): ZigExecConfigTest {
        val clone = super.clone()
        clone.filePath = filePath.clone()
        clone.compilerArgs = compilerArgs.clone()
        clone.optimization = optimization.clone()
        return clone
    }

    override fun getConfigurables(): List<ZigConfigurable<*>> {
        return super.getConfigurables() + listOf(filePath, optimization, compilerArgs)
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): ZigProfileState<ZigExecConfigTest> {
        return ZigProfileStateTest(environment, this)
    }
}