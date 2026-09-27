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

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory

/** The nearest ancestor directory of a file that contains a `build.zig`, i.e. its owning package root.
 *  Cached per directory since a directory test run resolves the same ancestors over and over. */
object ZigTestPackages {
    private val cache = ConcurrentHashMap<Path, Path?>()

    fun nearestPackageDir(file: Path): Path? {
        val start = (if (file.isDirectory()) file else file.parent) ?: return null
        return nearestFrom(start.normalize())
    }

    private fun nearestFrom(dir: Path): Path? = cache.getOrPut(dir) {
        if (Files.exists(dir.resolve("build.zig"))) dir else dir.parent?.let { nearestFrom(it) }
    }
}
