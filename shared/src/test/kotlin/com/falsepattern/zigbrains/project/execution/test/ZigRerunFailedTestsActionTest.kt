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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

/** Covers the pure part of [ZigRerunFailedTestsAction]: the rerun target derived from (file, test name) pairs. */
class ZigRerunFailedTestsActionTest {
    @Test
    fun singleFileRerunsThatFile() {
        val file = Path.of("/repo/src/lexer.zig")
        assertEquals(file, ZigRerunFailedTestsAction.coveringPath(listOf(file, file)))
    }

    @Test
    fun severalFilesRerunTheirDeepestCommonDirectory() {
        assertEquals(
            Path.of("/repo/src"),
            ZigRerunFailedTestsAction.coveringPath(listOf(Path.of("/repo/src/a/lexer.zig"), Path.of("/repo/src/b/c/parser.zig"), Path.of("/repo/src/root.zig"))),
        )
    }

    @Test
    fun oneFilterPerFailedTestQuotedLikeTheGutterConfig() {
        assertEquals(
            "--test-filter \"tokens\" --test-filter \"the parser eats 'quotes' too\"",
            ZigRerunFailedTestsAction.filterArgs(emptyList(), listOf("tokens", "the parser eats 'quotes' too", "tokens")),
        )
    }

    @Test
    fun filterWithADoubleQuoteFallsBackToSingleQuotes() {
        assertEquals("--test-filter 'say \"cheese\"'", ZigRerunFailedTestsAction.filterArgs(emptyList(), listOf("say \"cheese\"")))
    }

    @Test
    fun earlierFiltersAreReplacedAndOtherArgsKept() {
        // A rerun of a rerun must not accumulate filters; unrelated compiler args survive, re-quoted when needed.
        assertEquals(
            "-lc \"-Dname=two words\" --test-filter \"tokens\"",
            ZigRerunFailedTestsAction.filterArgs(listOf("-lc", "--test-filter", "old one", "-Dname=two words", "--test-filter", "older"), listOf("tokens")),
        )
    }
}
