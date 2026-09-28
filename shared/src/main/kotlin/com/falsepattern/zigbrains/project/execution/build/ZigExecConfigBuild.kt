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

import com.falsepattern.zigbrains.ZigBrainsBundle
import com.falsepattern.zigbrains.project.buildscan.Serialization
import com.falsepattern.zigbrains.project.buildscan.zigBuildScan
import com.falsepattern.zigbrains.project.execution.base.*
import com.falsepattern.zigbrains.shared.ZBFeatures
import com.falsepattern.zigbrains.shared.cli.translateCommandline
import com.falsepattern.zigbrains.shared.element.readString
import com.falsepattern.zigbrains.shared.element.readStrings
import com.falsepattern.zigbrains.shared.element.writeString
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import org.jdom.Element
import org.jetbrains.annotations.Nls
import java.nio.file.Path
import javax.swing.Icon

class ZigExecConfigBuild(project: Project, factory: ConfigurationFactory): ZigExecConfig<ZigExecConfigBuild>(project, factory, ZigBrainsBundle.message("exec.type.build.label")) {
    var buildSteps = StepsConfigurable(project, "buildSteps", ZigBrainsBundle.message("exec.option.label.build.steps")) { workingDirectory.path }
        private set
    var extraArgs = ArgsConfigurable("compilerArgs", ZigBrainsBundle.message("exec.option.label.build.args"), true)
        private set

    /** True when this runs a finite test step (see [ZigBuildTestDiscovery.runsTests]): the only kind coverage can run. */
    fun runsTests() = ZigBuildTestDiscovery.runsTests(buildSteps.argsSplit() + extraArgs.argsSplit())
    var debugBuildSteps = StepsConfigurable(project, "debugBuildSteps", ZigBrainsBundle.message("exec.option.label.build.steps-debug")) { workingDirectory.path }
        private set
    var debugExtraArgs = ArgsConfigurable("debugCompilerArgs", ZigBrainsBundle.message("exec.option.label.build.args-debug"), true)
        private set
    var exePath = FilePathConfigurable("exePath", ZigBrainsBundle.message("exec.option.label.build.exe-path-debug"))
        private set
    var exeArgs = ArgsConfigurable("exeArgs", ZigBrainsBundle.message("exec.option.label.build.exe-args-debug"), true)
        private set

    @Throws(ExecutionException::class)
    override suspend fun buildCommandLineArgs(debug: Boolean): List<String> {
        val result = ArrayList<String>()
        result.add("build")
        val steps = if (debug) debugBuildSteps.argsSplit() else buildSteps.argsSplit()
        result.addAll(steps)
        result.addAll(if (debug) debugExtraArgs.argsSplit() else extraArgs.argsSplit())
        return result
    }

    override val suggestedName: String
        get() = ZigBrainsBundle.message("configuration.build.suggested-name")

    override fun clone(): ZigExecConfigBuild {
        val clone = super.clone()
        clone.buildSteps = buildSteps.clone().also { it.retarget { clone.workingDirectory.path } }
        clone.extraArgs = extraArgs.clone()
        clone.debugBuildSteps = debugBuildSteps.clone().also { it.retarget { clone.workingDirectory.path } }
        clone.debugExtraArgs = debugExtraArgs.clone()
        clone.exePath = exePath.clone()
        clone.exeArgs = exeArgs.clone()
        return clone
    }

    override fun getConfigurables(): List<ZigConfigurable<*>> {
        val baseCfg = super.getConfigurables() + listOf(buildSteps, extraArgs)
        return if (ZBFeatures.debug()) {
            baseCfg + listOf(debugBuildSteps, debugExtraArgs, exePath, exeArgs)
        } else {
            baseCfg
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): ZigProfileState<ZigExecConfigBuild> {
        return ZigProfileStateBuild(environment, this)
    }
}


class StepsConfigurable(
	@Transient private val project: Project,
	@Transient private val serializedName: String,
	@Transient @Nls private val guiName: String,
	@Transient private var workingDirectory: () -> Path? = { null }
) : ZigConfigurable<StepsConfigurable>, Cloneable {
	var args: String = ""

	override fun readExternal(element: Element) {
		args = element.readString(serializedName) ?: element.readStrings(serializedName)?.joinToString(separator = " ") { if (it.contains(' ')) "\"$it\"" else it } ?: ""
	}

	fun argsSplit(): List<String> {
		return translateCommandline(args)
	}

	override fun writeExternal(element: Element) {
		element.writeString(serializedName, args)
	}

	override fun createEditor(): ZigConfigurable.ZigConfigModule<StepsConfigurable> {
		return StepsConfigModule(project, serializedName, guiName, workingDirectory)
	}

	override fun clone(): StepsConfigurable {
		return super<Cloneable>.clone() as StepsConfigurable
	}

	// a plain clone() keeps the closure pointing at the ORIGINAL config's workingDirectory (shallow copy);
	// the owning config's clone() calls this right after, so the clone's completion reflects its own state
	fun retarget(workingDirectory: () -> Path?) {
		this.workingDirectory = workingDirectory
	}

	class StepsConfigModule(
		project: Project,
		private val serializedName: String,
		@Nls private val guiName: String,
		workingDirectory: () -> Path?
	) : ZigConfigurable.ZigConfigModule<StepsConfigurable> {
		private val argsField = TextFieldWithAutoCompletion(project, StepsTextFieldCompletionProvider(project, workingDirectory), true, "Steps...")

		override fun tryMatch(cfg: ZigConfigurable<*>): StepsConfigurable? {
			return if (cfg is StepsConfigurable && cfg.serializedName == serializedName) cfg else null
		}

		override fun apply(configurable: StepsConfigurable): Boolean {
			configurable.args = argsField.text
			return true
		}

		override fun reset(configurable: StepsConfigurable) {
			argsField.text = configurable.args
		}

		override fun construct(p: Panel): Unit = with(p) {
			row(guiName) {
				cell(argsField).align(AlignX.FILL)
			}
		}

		override fun dispose() {

		}

		class StepsTextFieldCompletionProvider(
			private val project: Project,
			private val workingDirectory: () -> Path?
		) : TextFieldWithAutoCompletionListProvider<Serialization.Step>(emptyList()) {
			// Evaluated fresh on every popup: the config's working directory may not have been reset into
			// `workingDirectory` yet at construction time (modules are built before resetEditorFrom runs).
			// This reads the config's last-applied (saved) working directory, not whatever is currently
			// typed but not yet applied in the working-directory field -- there is no live hookup between
			// the two editor modules, only the shared config object both eventually read/write.
			override fun getItems(prefix: String?, cached: Boolean, parameters: CompletionParameters?): Collection<Serialization.Step> {
				val scan = project.zigBuildScan
				val dir = workingDirectory()?.normalize()
				// deepest-prefix match: a working directory of "cli/src" (inside the "cli" package, not
				// exactly at its root) still gets "cli"'s steps, not a fall-through to the root project's.
				val matching = dir
					?.let { d -> scan.projects.filter { runCatching { d.startsWith(Path.of(it.path).normalize()) }.getOrDefault(false) } }
					?.maxByOrNull { it.path.length }
				return ((matching ?: scan.rootProject)?.steps ?: emptyList()).sortedBy { it.name }
			}

			override fun getLookupString(item: Serialization.Step): String =
				item.name

			override fun getIcon(item: Serialization.Step): Icon? {
				return when(item.kind) {
					"install" -> AllIcons.Actions.Install
					"uninstall" -> AllIcons.Actions.Uninstall
					else -> AllIcons.RunConfigurations.TestState.Run
				}
			}

			override fun getTypeText(item: Serialization.Step): String? =
				item.kind

			override fun getTailText(item: Serialization.Step): String? =
				"   ${item.description}"
		}
	}
}
