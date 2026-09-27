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

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.module.Module
import java.nio.file.Path

/**
 * "Rerun Failed Tests" for the Zig test tree: reruns a clone of the finished [ZigExecConfigTest] narrowed to
 * the failed tests' file (or the smallest directory covering them) with one `--test-filter` per failed test.
 */
class ZigRerunFailedTestsAction(consoleView: ConsoleView, private val properties: ZigTestConsoleProperties) : AbstractRerunFailedTestsAction(consoleView) {
    init {
        init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        // The failed test's file is the one that ran it (an imported file's tests run through the importer).
        val failed = getFailedTests(environment.project).mapNotNull { proxy ->
            val (file, fullName) = proxy.locationUrl?.let(ZigTestLocator::parse) ?: return@mapNotNull null
            file to ZigTestOutputParser.displayNameOf(fullName)
        }
        if (failed.isEmpty()) return null
        val config = properties.config.clone()
        config.filePath.path = coveringPath(failed.map { it.first })
        config.compilerArgs.args = filterArgs(properties.config.compilerArgs.argsSplit(), failed.map { it.second })
        return object : MyRunProfile(config) {
            override fun getModules(): Array<Module> = Module.EMPTY_ARRAY

            override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = ZigProfileStateTest(environment, config)
        }
    }

    companion object {
        /** The narrowest run target for [files]: the file itself for one, else their deepest common ancestor directory. */
        fun coveringPath(files: Collection<Path>): Path {
            val distinct = files.distinct()
            if (distinct.size == 1) return distinct[0]
            // Failures across several files become one directory run with the union of filters: `--test-filter`
            // is a substring match, so a same-named test in another file under that directory runs too, which
            // only costs an extra pass.
            var common: Path? = distinct[0].parent
            for (file in distinct) {
                while (common != null && !file.startsWith(common)) common = common.parent
            }
            return common ?: distinct[0]
        }

        /** [baseArgs] with any earlier `--test-filter <text>` pairs replaced by one per name in [names], as a compiler-args string. */
        fun filterArgs(baseArgs: List<String>, names: Collection<String>): String {
            val kept = ArrayList<String>()
            var dropNext = false
            for (arg in baseArgs) {
                when {
                    dropNext -> dropNext = false
                    arg == "--test-filter" -> dropNext = true
                    else -> kept.add(arg)
                }
            }
            val filters = names.distinct().flatMap { listOf("--test-filter", quote(it)) }
            return (kept.map { if (it.any { c -> c.isWhitespace() || c == '"' || c == '\'' }) quote(it) else it } + filters).joinToString(" ")
        }

        // Same quoting as ZigConfigProducerTest: double quotes unless the name itself contains one.
        private fun quote(arg: String): String = if ('"' in arg) "'$arg'" else "\"$arg\""
    }
}
