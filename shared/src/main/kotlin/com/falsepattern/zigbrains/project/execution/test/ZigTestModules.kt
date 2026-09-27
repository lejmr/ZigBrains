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

import com.falsepattern.zigbrains.project.buildscan.Serialization
import com.falsepattern.zigbrains.shared.sanitizedPathString
import java.nio.file.Path

/**
 * Pure helper turning a build-scan result into the `--dep`/`-M` flags `zig test` needs to see a file's
 * module graph (its imports), rather than testing it as a standalone file. No dependency on IntelliJ APIs.
 */
object ZigTestModules {
    /**
     * Returns the `--dep`/`-M` flags to insert after `test` (before `-Mroot=<file>`'s own optimization/
     * compiler flags), or null if [file] isn't part of any scanned project/module, or its module has no
     * imports (in which case the caller should fall back to plain `test <file>`).
     */
    fun moduleArgs(projects: List<Serialization.Project>, file: Path): List<String>? {
        val filePath = file.normalize()
        val project = projects
            .filter { runCatching { filePath.startsWith(Path.of(it.path).normalize()) }.getOrDefault(false) }
            .maxByOrNull { it.path.length }
            ?: return null
        val rootModule = project.modules
            .filter { module ->
                val dir = Path.of(module.root).normalize().parent ?: return@filter false
                runCatching { filePath.startsWith(dir) }.getOrDefault(false)
            }
            .maxByOrNull { Path.of(it.root).normalize().parent?.toString()?.length ?: 0 }
            ?: return null
        if (rootModule.imports.isEmpty()) return null

        val rootFileArg = file.sanitizedPathString ?: filePath.toString()
        val args = ArrayList<String>()
        val visited = HashSet<Pair<Int, Int>>()
        val queue = ArrayDeque<Triple<String?, Serialization.Module, Boolean>>()
        queue.add(Triple(null, rootModule, true))
        while (queue.isNotEmpty()) {
            val (name, module, isRoot) = queue.removeFirst()
            for (import in module.imports) {
                args.add("--dep")
                args.add(import.name)
            }
            args.add(if (isRoot) "-Mroot=$rootFileArg" else "-M$name=${module.root}")
            for (import in module.imports) {
                val key = import.owner to import.module
                if (!visited.add(key)) continue
                val importedProject = projects.getOrNull(import.owner) ?: continue
                val importedModule = importedProject.modules.getOrNull(import.module) ?: continue
                queue.add(Triple(import.name, importedModule, false))
            }
        }
        return args
    }
}
