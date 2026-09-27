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

package com.falsepattern.zigbrains

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import java.nio.file.Path

/**
 * Headless platform test with the Zig plugin loaded and the project's content root on the real file system
 * (not the in-memory `temp:///` of [com.intellij.testFramework.fixtures.BasePlatformTestCase]): the run
 * configuration producers and [com.falsepattern.zigbrains.project.execution.test.ZigTestLocator] resolve
 * files through `toNioPath` and `LocalFileSystem`, which the light fixture cannot serve.
 */
abstract class ZigPlatformTestCase : UsefulTestCase() {
    protected lateinit var myFixture: CodeInsightTestFixture

    protected val project: Project get() = myFixture.project

    override fun setUp() {
        super.setUp()
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val builder = factory.createFixtureBuilder(name)
        myFixture = factory.createCodeInsightFixture(builder.fixture, TempDirTestFixtureImpl())
        builder.addModule(EmptyModuleFixtureBuilder::class.java).addContentRoot(myFixture.tempDirPath)
        myFixture.setUp()
    }

    override fun tearDown() {
        try {
            myFixture.tearDown()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Creates `relativePath` under the content root and opens it in the editor, honouring a `<caret>` marker. */
    protected fun configure(relativePath: String, text: String): PsiFile {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(relativePath, text).virtualFile)
        return myFixture.file
    }

    protected fun nioPath(file: PsiFile): Path = file.virtualFile.toNioPath()

    protected fun elementAtCaret(): PsiElement = myFixture.file.findElementAt(myFixture.caretOffset)!!

    /** The context a gutter icon or the editor context menu hands to the run configuration producers. */
    protected fun contextOf(element: PsiElement): ConfigurationContext =
        ConfigurationContext.createEmptyContextForLocation(PsiLocation(project, element))

    protected fun contextAtCaret(): ConfigurationContext = contextOf(elementAtCaret())

    /** The actions of the run gutter icon on the caret line (the platform merges every contributor's Info into one icon). */
    protected fun gutterActionsAtCaret(): List<AnAction> {
        return myFixture.findGuttersAtCaret().flatMap { mark ->
            val group = (mark as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.popupMenuActions ?: return@flatMap emptyList()
            group.getChildren(null).toList()
        }
    }

    protected fun gutterActionTextsAtCaret(): List<String> = gutterActionsAtCaret().mapNotNull { it.templatePresentation.text }
}
