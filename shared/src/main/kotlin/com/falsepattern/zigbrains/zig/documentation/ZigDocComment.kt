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

package com.falsepattern.zigbrains.zig.documentation

/**
 * Strips a repeated line-comment prefix (`///` or `//!`) from every line of a doc comment's raw text and
 * joins what is left, so it can be handed to [com.intellij.markdown.utils.convertMarkdownToHtml] the same
 * way for a declaration's `///` block as for a file's `//!` block.
 */
fun stripDocCommentPrefix(rawText: String, prefix: String): String =
    rawText.lines().joinToString(separator = "\n") { line ->
        val idx = line.indexOf(prefix)
        if (idx < 0 || idx + prefix.length > line.length) line else line.substring(idx + prefix.length)
    }
