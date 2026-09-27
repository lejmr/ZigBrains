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

import com.falsepattern.zigbrains.project.execution.base.ZigProfileState
import com.falsepattern.zigbrains.project.execution.test.ZigExecConfigTest
import com.falsepattern.zigbrains.project.execution.test.ZigProfileStateTest
import com.falsepattern.zigbrains.project.run.ZigProgramRunner
import com.falsepattern.zigbrains.project.toolchain.base.ZigToolchain
import com.falsepattern.zigbrains.shared.coroutine.withEDTContext
import com.falsepattern.zigbrains.zig.ZigFileType
import com.falsepattern.zigbrains.zig.psi.ZigFile
import com.intellij.coverage.BaseCoverageSuite
import com.intellij.coverage.CoverageAnnotator
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageExecutor
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageHelper
import com.intellij.coverage.CoverageLoadErrorReporter
import com.intellij.coverage.CoverageLoadingResult
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageRunnerData
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.FailedCoverageLoadingResult
import com.intellij.coverage.SuccessCoverageLoadingResult
import com.intellij.coverage.SimpleCoverageAnnotator
import com.intellij.coverage.view.CoverageViewExtension
import com.intellij.coverage.view.DirectoryCoverageViewExtension
import com.intellij.execution.configurations.ConfigurationInfoProvider
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import java.io.IOException
import java.nio.file.Path
import javax.xml.stream.XMLStreamException

/** Runs a Zig test configuration under the "Run with Coverage" executor and hands the process to the coverage framework. */
class ZigCoverageProgramRunner : ZigProgramRunner<ZigProfileStateTest>(CoverageExecutor.EXECUTOR_ID) {
    override suspend fun execute(state: ZigProfileStateTest, toolchain: ZigToolchain, environment: ExecutionEnvironment): RunContentDescriptor? {
        val exec = state.execute(environment.executor, this)
        return withEDTContext(ModalityState.any()) {
            // attachToProcess registers the suite with invokeAndWait, which only runs inline on the EDT.
            CoverageHelper.attachToProcess(state.configuration, exec.processHandler, environment.runnerSettings)
            RunContentBuilder(exec, environment).showRunContent(null)
        }
    }

    override fun castProfileState(state: ZigProfileState<*>) = state as? ZigProfileStateTest

    override fun canRun(executorId: String, profile: RunProfile) = this.executorId == executorId && profile is ZigExecConfigTest

    override fun createConfigurationData(settingsProvider: ConfigurationInfoProvider): RunnerSettings = CoverageRunnerData()

    override fun getRunnerId() = "ZigCoverageRunner"
}

/** Coverage settings of a Zig test configuration; the session "file" is its kcov output dir. */
class ZigCoverageEnabledConfiguration(configuration: ZigExecConfigTest) :
    CoverageEnabledConfiguration(configuration, CoverageRunner.getInstance(ZigCoverageRunner::class.java)) {
    override fun getCoverageFilePath() = (configuration as ZigExecConfigTest).coverageDir()?.toString()
}

class ZigCoverageSuite : BaseCoverageSuite {
    constructor() : super()
    constructor(name: String, project: Project, runner: CoverageRunner, fileProvider: CoverageFileProvider, timestamp: Long) :
            super(name, project, runner, fileProvider, timestamp)

    override fun getCoverageEngine(): CoverageEngine = CoverageEngine.EP_NAME.findExtensionOrFail(ZigCoverageEngine::class.java)
}

// Keys files by their absolute path; SimpleCoverageAnnotator does the per-directory percentages from that.
@Service(Service.Level.PROJECT)
class ZigCoverageAnnotator(project: Project) : SimpleCoverageAnnotator(project)

class ZigCoverageEngine : CoverageEngine() {
    override fun getPresentableText() = CoverageBundle.message("coverage.engine.name")

    override fun isApplicableTo(conf: RunConfigurationBase<*>) = conf is ZigExecConfigTest

    override fun createCoverageEnabledConfiguration(conf: RunConfigurationBase<*>): CoverageEnabledConfiguration =
        ZigCoverageEnabledConfiguration(conf as ZigExecConfigTest)

    override fun createCoverageSuite(name: String, project: Project, runner: CoverageRunner, fileProvider: CoverageFileProvider, timestamp: Long): CoverageSuite =
        ZigCoverageSuite(name, project, runner, fileProvider, timestamp)

    override fun createCoverageSuite(name: String, project: Project, runner: CoverageRunner, fileProvider: CoverageFileProvider, timestamp: Long, config: CoverageEnabledConfiguration): CoverageSuite =
        ZigCoverageSuite(name, project, runner, fileProvider, timestamp)

    override fun createEmptyCoverageSuite(runner: CoverageRunner): CoverageSuite = ZigCoverageSuite()

    override fun getCoverageAnnotator(project: Project): CoverageAnnotator = project.service<ZigCoverageAnnotator>()

    override fun coverageEditorHighlightingApplicableTo(psiFile: PsiFile) = psiFile is ZigFile

    override fun acceptedByFilters(psiFile: PsiFile, suite: CoverageSuitesBundle): Boolean {
        val file = psiFile.virtualFile ?: return false
        return ProjectFileIndex.getInstance(psiFile.project).isInContent(file)
    }

    override fun getQualifiedNames(sourceFile: PsiFile): Set<String> = setOfNotNull(sourceFile.virtualFile?.path)

    // Internal API, no public alternative: the editor annotator looks lines up only through this overload (the default null
    // leaves the gutter empty); the bundled RustRover engine overrides it the same way.
    override fun getQualifiedName(outputFile: Path, sourceFile: PsiFile): String? = sourceFile.virtualFile?.path

    // Internal API, no public alternative: SimpleCoverageAnnotator only counts files this accepts (default false) for the
    // tool window and project view percentages; the bundled RustRover engine overrides it too.
    override fun coverageProjectViewStatisticsApplicableTo(fileOrDir: VirtualFile) = !fileOrDir.isDirectory && fileOrDir.fileType == ZigFileType

    // The default is null, which leaves the Coverage tool window empty.
    override fun createCoverageViewExtension(project: Project, suiteBundle: CoverageSuitesBundle): CoverageViewExtension =
        DirectoryCoverageViewExtension(project, getCoverageAnnotator(project), suiteBundle)
}

class ZigCoverageRunner : CoverageRunner() {
    override fun loadCoverageData(sessionDataFile: Path, baseCoverageSuite: CoverageSuite?, reporter: CoverageLoadErrorReporter): CoverageLoadingResult {
        val report = try {
            KcovReport.load(sessionDataFile)
        } catch (e: XMLStreamException) {
            return FailedCoverageLoadingResult("Malformed kcov report in $sessionDataFile: ${e.message}", e)
        } catch (e: IOException) {
            return FailedCoverageLoadingResult("Cannot read kcov report $sessionDataFile: ${e.message}", e)
        }
        if (report.isEmpty()) return FailedCoverageLoadingResult("No kcov coverage data in $sessionDataFile")
        val data = ProjectData()
        for ((path, hits) in report) {
            // ClassData.setLines wants an array indexed by line number.
            val lines = arrayOfNulls<LineData>(hits.lastKey() + 1)
            for ((number, count) in hits) {
                lines[number] = LineData(number, null).apply { this.hits = count }
            }
            data.getOrCreateClassData(path).setLines(lines)
        }
        return SuccessCoverageLoadingResult(data)
    }

    override fun canBeLoaded(candidate: Path) = KcovReport.isReport(candidate)

    override fun getPresentableName() = "kcov"

    override fun getId() = "zig-kcov"

    override fun getDataFileExtension() = "xml"

    override fun acceptsCoverageEngine(engine: CoverageEngine) = engine is ZigCoverageEngine
}
