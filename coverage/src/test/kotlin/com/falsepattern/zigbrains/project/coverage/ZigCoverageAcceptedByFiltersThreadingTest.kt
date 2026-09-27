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

import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Regression test for the read-action fix in [ZigCoverageEngine.acceptedByFilters] (see the fix site's comment). */
class ZigCoverageAcceptedByFiltersThreadingTest : BasePlatformTestCase() {
    fun `test acceptedByFilters is safe to call from a background thread with no read action held`() {
        val psiFile = myFixture.configureByText("a.zig", "pub fn main() void {}\n")
        val bundle = CoverageSuitesBundle(ZigCoverageSuite())
        val engine = ZigCoverageEngine()

        val executor = Executors.newSingleThreadExecutor()
        try {
            // No ReadAction/EDT here on purpose: this mirrors CoverageDataAnnotationsManager's real call site.
            val accepted = executor.submit<Boolean> { engine.acceptedByFilters(psiFile, bundle) }
                .get(10, TimeUnit.SECONDS)
            assertTrue("a file inside the project content root should be accepted", accepted)
        } finally {
            executor.shutdown()
        }
    }
}
