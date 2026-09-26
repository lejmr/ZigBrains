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
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.MalformedInputException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory

class ZigExecConfigTest(project: Project, factory: ConfigurationFactory): ZigExecConfig<ZigExecConfigTest>(project, factory, ZigBrainsBundle.message("exec.type.test.label")) {
    var filePath = FilePathConfigurable("filePath", ZigBrainsBundle.message("exec.option.label.file-path"), allowDirectories = true)
        private set
    var optimization = OptimizationConfigurable("optimization")
        private set
    var compilerArgs = ArgsConfigurable("compilerArgs", ZigBrainsBundle.message("exec.option.label.compiler-args"), true)
        private set

    /**
     * The individual `.zig` files a single run of this configuration should invoke `zig test` on.
     * A file path resolves to itself; a directory path resolves to every `.zig` file recursively
     * under it that has a line starting with `test` (skipping hidden and zig-cache/zig-out directories),
     * minus the ones directly `@import`ed by another selected file (their tests already run through
     * the importer, so running them again on their own would just duplicate the run).
     *
     * A per-file `zig test` is not `zig build test`: it has no module graph, so a file that is only
     * ever reached by `@import` from outside its own directory (or from a named build module) may fail
     * to compile standalone; only its importer can run it.
     */
    @Throws(ExecutionException::class)
    fun testFiles(): List<Path> {
        val path = filePath.path ?: throw ExecutionException(ZigBrainsBundle.message("exception.zig.empty-file-path"))
        if (!path.isDirectory()) {
            return listOf(path)
        }
        val excludedDirs = setOf("zig-cache", ".zig-cache", "zig-out")
        val hasTestDecl = Regex("(?m)^\\s*test\\b")
        val importRegex = Regex("""@import\("([^"]+\.zig)"\)""")
        val candidates = ArrayList<Pair<Path, String>>()
        try {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName.toString()
                    return if (dir != path && (name in excludedDirs || name.startsWith("."))) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && file.fileName.toString().endsWith(".zig")) {
                        val content = try {
                            Files.readString(file)
                        } catch (e: MalformedInputException) {
                            return FileVisitResult.CONTINUE
                        } catch (e: IOException) {
                            return FileVisitResult.CONTINUE
                        }
                        if (hasTestDecl.containsMatchIn(content)) {
                            candidates.add(file to content)
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: IOException) {
            throw ExecutionException(e)
        } catch (e: UncheckedIOException) {
            throw ExecutionException(e)
        }
        val selected = candidates.mapTo(HashSet()) { it.first }
        val imported = HashSet<Path>()
        for ((file, content) in candidates) {
            for (match in importRegex.findAll(content)) {
                val target = file.resolveSibling(match.groupValues[1]).normalize()
                if (target in selected) {
                    imported.add(target)
                }
            }
        }
        val files = candidates.map { it.first }.filterNot { it in imported }.sorted()
        if (files.isEmpty()) {
            throw ExecutionException(ZigBrainsBundle.message("exception.zig.no-test-files", path))
        }
        return files
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