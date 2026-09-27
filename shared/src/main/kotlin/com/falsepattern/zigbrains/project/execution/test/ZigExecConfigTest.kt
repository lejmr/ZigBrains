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
import com.falsepattern.zigbrains.project.buildscan.zigBuildScan
import com.falsepattern.zigbrains.project.execution.base.ArgsConfigurable
import com.falsepattern.zigbrains.project.execution.base.FilePathConfigurable
import com.falsepattern.zigbrains.project.execution.base.OptimizationConfigurable
import com.falsepattern.zigbrains.project.execution.base.OptimizationLevel
import com.falsepattern.zigbrains.project.execution.base.ZigConfigurable
import com.falsepattern.zigbrains.project.execution.base.ZigExecConfig
import com.falsepattern.zigbrains.project.execution.base.ZigProfileState
import com.falsepattern.zigbrains.shared.sanitizedPathString
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.NioFiles
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.MalformedInputException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable

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
        val files = candidates.map { it.first }.filterNot { it in imported }
            .sortedWith(compareBy({ ZigTestPackages.nearestPackageDir(it)?.toString() ?: "" }, { it.toString() }))
        if (files.isEmpty()) {
            throw ExecutionException(ZigBrainsBundle.message("exception.zig.no-test-files", path))
        }
        return files
    }

    @Throws(ExecutionException::class)
    suspend fun buildCommandLineArgs(debug: Boolean, file: Path, coverage: Boolean = false): List<String> {
        val moduleArgs = ZigTestModules.moduleArgs(project.zigBuildScan.projects, file)
        val fileArg = if (moduleArgs == null) file.sanitizedPathString ?: throw ExecutionException(ZigBrainsBundle.message("exception.zig.empty-file-path")) else null
        // kcov's line data is unreliable in release modes, so coverage runs always build Debug.
        if (coverage) return assembleTestArgs(false, true, OptimizationLevel.Debug.name, compilerArgs.argsSplit(), moduleArgs, fileArg)
        return assembleTestArgs(debug, optimization.forced, optimization.level.name, compilerArgs.argsSplit(), moduleArgs, fileArg)
    }

    @Throws(ExecutionException::class)
    override suspend fun buildCommandLineArgs(debug: Boolean): List<String> {
        return buildCommandLineArgs(debug, filePath.path ?: throw ExecutionException(ZigBrainsBundle.message("exception.zig.empty-file-path")))
    }

    /** Where a coverage run of this configuration writes kcov's output, or null without a project dir. */
    fun coverageDir(): Path? {
        val base = project.basePath ?: return null
        return Path(base, ".zig-cache", "zigbrains-coverage", name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    }

    /** Clears [coverageDir] and returns the `zig test` args that run each test binary under kcov into it. */
    @Throws(ExecutionException::class)
    fun prepareCoverageArgs(): List<String> {
        // An IDE started from the Dock has no shell PATH, so fall back to the Homebrew locations.
        val kcov = PathEnvironmentVariableUtil.findInPath("kcov")?.toPath()
            ?: listOf("/opt/homebrew/bin/kcov", "/usr/local/bin/kcov").map { Path(it) }.firstOrNull { it.isExecutable() }
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

    companion object {
        /** Id of the platform's "Run with Coverage" executor (the coverage module registers its runner). */
        const val COVERAGE_EXECUTOR_ID = "Coverage"

        /** `--test-cmd` args that make `zig test` run the test binary as `kcov --include-path=<includeDir> <outDir> <binary>`. */
        // --include-path matches kcov's resolved paths: a symlinked project dir or a comma in the path (kcov's list
        // separator) drops everything, and files outside the project (other packages, std) are never reported.
        fun kcovArgs(kcov: String, includeDir: String, outDir: String): List<String> =
            listOf("--test-cmd", kcov, "--test-cmd", "--include-path=$includeDir", "--test-cmd", outDir, "--test-cmd-bin")

        /**
         * Pure assembly of a `zig test` argument list: `-O`/compiler args are per-module options that zig
         * applies to whichever `-M`/positional file comes right after, so they must precede [moduleArgs]
         * (or [fileArg]), not follow it -- a trailing `-O` is silently ignored. Exactly one of [moduleArgs]
         * (module-aware) or [fileArg] (plain positional file) is non-null.
         */
        fun assembleTestArgs(debug: Boolean, forceOptimization: Boolean, optimizationLevel: String, compilerArgs: List<String>, moduleArgs: List<String>?, fileArg: String?): List<String> {
            val result = ArrayList<String>()
            result.add("test")
            if (!debug || forceOptimization) {
                result.addAll(listOf("-O", optimizationLevel))
            }
            result.addAll(compilerArgs)
            if (moduleArgs != null) {
                result.addAll(moduleArgs)
            } else {
                result.add(fileArg!!)
            }
            if (debug) {
                result.add("--test-no-exec")
            }
            return result
        }
    }
}