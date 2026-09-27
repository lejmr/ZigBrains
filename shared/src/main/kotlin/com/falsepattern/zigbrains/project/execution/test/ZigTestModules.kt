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
     * Returns the `--dep`/`-M` flags to insert between `test`'s optimization/compiler flags and
     * `--test-no-exec` (in place of the plain positional file), or null if [file] isn't part of any
     * scanned project/module, or its module has no imports (in which case the caller should fall back to
     * plain `test <file>`).
     */
    fun moduleArgs(projects: List<Serialization.Project>, file: Path): List<String>? {
        val filePath = file.normalize()
        val projectIdx = projects.indices
            .filter { idx -> runCatching { filePath.startsWith(Path.of(projects[idx].path).normalize()) }.getOrDefault(false) }
            .maxByOrNull { projects[it].path.length }
            ?: return null
        val project = projects[projectIdx]

        // C1: a module whose builder.zig couldn't resolve an absolute root (or an older, unfixed scan)
        // reports a relative/placeholder root -- there's no directory to test it as an ancestor of.
        val candidates = project.modules.withIndex().filter { (_, module) ->
            val root = Path.of(module.root)
            val dir = if (root.isAbsolute) root.normalize().parent else null
            dir != null && runCatching { filePath.startsWith(dir) }.getOrDefault(false)
        }
        if (candidates.isEmpty()) return null

        val (rootModuleIdx, rootModule) = pickRootModule(candidates, filePath)
        if (rootModule.imports.isEmpty()) return null

        val rootFileArg = file.sanitizedPathString ?: filePath.toString()
        // seeded so a module that (directly or transitively) imports itself resolves back to "root"
        // instead of being given a second, duplicate -M definition
        val moduleNames = HashMap<Pair<Int, Int>, String>()
        moduleNames[projectIdx to rootModuleIdx] = "root"
        val usedNames = hashSetOf("root")

        val args = ArrayList<String>()
        val queue = ArrayDeque<Triple<String, Serialization.Module, Boolean>>()
        queue.add(Triple("root", rootModule, true))
        while (queue.isNotEmpty()) {
            val (name, module, isRoot) = queue.removeFirst()
            for (import in module.imports) {
                val key = import.owner to import.module
                val importedProject = projects.getOrNull(import.owner) ?: continue
                val importedModule = importedProject.modules.getOrNull(import.module) ?: continue
                var isNewName = false
                val depName = moduleNames.getOrPut(key) {
                    isNewName = true
                    uniqueName(import.name, usedNames)
                }
                // import.name is the name this module's source sees; depName is the -M flag's own name,
                // which only differs from it on a collision with another imported module (verified on
                // zig 0.16: `--dep calc=m1 -Mm1=...` and, for a self-import, `--dep self=root` both work)
                args.add("--dep")
                args.add("${import.name}=$depName")
                if (isNewName) queue.add(Triple(depName, importedModule, false))
            }
            args.add(if (isRoot) "-Mroot=$rootFileArg" else "-M$name=${module.root}")
        }
        return args
    }

    private fun uniqueName(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        var suffix = 2
        while (!used.add("$base$suffix")) suffix++
        return "$base$suffix"
    }

    /**
     * Chooses which of a project's modules "owns" [filePath] when more than one root directory is an
     * ancestor of it: a module whose root *is* the file wins outright; otherwise the deepest root
     * directory wins; a real tie (e.g. the `zig init` layout, `src/root.zig` and `src/main.zig` sharing
     * `src/`) goes to whichever tied module has more imports, or -- if that's tied too -- the union of
     * every tied module's imports under the first one's identity. This is still a guess: `zig` itself
     * doesn't record which file within a shared directory a module "is", beyond its own root.
     */
    private fun pickRootModule(candidates: List<IndexedValue<Serialization.Module>>, filePath: Path): Pair<Int, Serialization.Module> {
        val exact = candidates.firstOrNull { (_, module) -> Path.of(module.root).normalize() == filePath }
        if (exact != null) return exact.index to exact.value

        val deepest = candidates.maxOf { (_, module) -> Path.of(module.root).normalize().parent?.toString()?.length ?: 0 }
        val deepestCandidates = candidates.filter { (_, module) -> (Path.of(module.root).normalize().parent?.toString()?.length ?: 0) == deepest }
        val maxImports = deepestCandidates.maxOf { (_, module) -> module.imports.size }
        val tied = deepestCandidates.filter { (_, module) -> module.imports.size == maxImports }
        val first = tied.first()
        if (tied.size == 1) return first.index to first.value

        val mergedImports = tied.flatMap { (_, module) -> module.imports }.distinctBy { it.owner to it.module }
        return first.index to first.value.copy(imports = mergedImports)
    }
}
