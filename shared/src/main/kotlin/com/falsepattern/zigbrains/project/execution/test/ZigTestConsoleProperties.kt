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
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator

class ZigTestConsoleProperties(config: ZigExecConfigTest, executor: Executor) :
    SMTRunnerConsoleProperties(config, FRAMEWORK_NAME, executor), SMCustomMessagesParsing {

    override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter {
        return ZigTestEventsConverter(testFrameworkName, consoleProperties)
    }

    override fun getTestLocator(): SMTestLocator = ZigTestLocator

    companion object {
        const val FRAMEWORK_NAME = "Zig Test"
    }
}
