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

import com.falsepattern.zigbrains.project.execution.test.ZigTestConsoleProperties
import com.intellij.execution.Executor
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import java.nio.file.Path

/**
 * A fuzz build has no discrete, rerunnable test list the way [ZigTestConsoleProperties] assumes (one
 * long-running process, not a per-test compile-and-run), so it gets its own minimal properties instead of
 * misusing that one: just enough to wire [ZigFuzzEventsConverter] into the SM console.
 */
class ZigFuzzConsoleProperties(config: ZigExecConfigBuild, executor: Executor) :
    SMTRunnerConsoleProperties(config, ZigTestConsoleProperties.FRAMEWORK_NAME, executor), SMCustomMessagesParsing {

    private val workDir: Path = config.workingDirectory.path ?: Path.of("")

    override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter =
        ZigFuzzEventsConverter(testFrameworkName, consoleProperties, workDir)
}
