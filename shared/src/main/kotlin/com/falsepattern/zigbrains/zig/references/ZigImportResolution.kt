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

package com.falsepattern.zigbrains.zig.references

import com.falsepattern.zigbrains.project.buildscan.Serialization
import java.nio.file.Path

/**
 * Resolves `@import("...")` strings to a file, pure and IDE-independent so it is testable the same way as
 * [com.falsepattern.zigbrains.project.execution.test.ZigTestModules]. Two shapes, matching zig itself:
 * a path ending in `.zig` (or starting with `./`/`../`) is a sibling file, resolved relative to the
 * importing file; anything else is a module name, resolved through the build-scan's import graph (the
 * same data [com.falsepattern.zigbrains.project.execution.test.ZigTestModules] uses for `--dep` flags).
 */
object ZigImportResolution {
    fun resolve(projects: List<Serialization.Project>, fromFile: Path, importString: String): Path? {
        if (importString.endsWith(".zig") || importString.startsWith("./") || importString.startsWith("../")) {
            return fromFile.resolveSibling(importString).normalize()
        }
        val module = ownerModule(projects, fromFile) ?: return null
        val import = module.imports.firstOrNull { it.name == importString } ?: return null
        val target = projects.getOrNull(import.owner)?.modules?.getOrNull(import.module) ?: return null
        val root = Path.of(target.root)
        return if (root.isAbsolute) root else null
    }

    // A simplified, best-effort version of ZigTestModules.pickRootModule: reference resolution only needs
    // *a* reasonable owning module, not a guaranteed-correct one for an ambiguous shared directory.
    private fun ownerModule(projects: List<Serialization.Project>, filePath: Path): Serialization.Module? {
        val normalized = filePath.normalize()
        val project = projects
            .filter { runCatching { normalized.startsWith(Path.of(it.path).normalize()) }.getOrDefault(false) }
            .maxByOrNull { it.path.length }
            ?: return null
        val candidates = project.modules.filter { module ->
            val root = Path.of(module.root)
            val dir = if (root.isAbsolute) root.normalize().parent else null
            dir != null && runCatching { normalized.startsWith(dir) }.getOrDefault(false)
        }
        return candidates.maxByOrNull { (Path.of(it.root).normalize().parent?.toString()?.length ?: 0) }
    }
}
