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

package com.falsepattern.zigbrains.project.coverage

import com.falsepattern.zigbrains.project.execution.build.ZigConfigTypeBuild
import com.falsepattern.zigbrains.project.execution.build.ZigExecConfigBuild
import com.intellij.coverage.CoverageExecutor
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.execution.runners.ProgramRunner
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * "Run with Coverage" on a `zig build` configuration (what the Zig tool window's test step creates): offered for a
 * test step, whose rediscovered `zig test` re-runs get kcov as their --test-cmd, and not for anything else.
 */
class ZigBuildCoverageTest : BasePlatformTestCase() {
    private fun buildConfig(steps: String): ZigExecConfigBuild {
        val type = ConfigurationTypeUtil.findConfigurationType(ZigConfigTypeBuild::class.java)
        return (type.configurationFactories.single().createTemplateConfiguration(project) as ZigExecConfigBuild)
            .apply { buildSteps.args = steps }
    }

    private fun coverageRunner(config: ZigExecConfigBuild) = ProgramRunner.getRunner(CoverageExecutor.EXECUTOR_ID, config)

    fun testATestStepCanRunWithCoverage() {
        val config = buildConfig("test")
        assertTrue(coverageRunner(config) is ZigCoverageProgramRunner)
        assertNotNull(CoverageEnabledConfiguration.getOrCreateIfApplicable(config))
    }

    fun testANamedTestStepCanRunWithCoverage() {
        assertTrue(coverageRunner(buildConfig("test-unit")) is ZigCoverageProgramRunner)
    }

    fun testARunStepCannotRunWithCoverage() {
        assertNull(coverageRunner(buildConfig("run")))
    }

    fun testAFuzzingTestStepCannotRunWithCoverage() {
        assertNull(coverageRunner(buildConfig("test --fuzz")))
    }
}
