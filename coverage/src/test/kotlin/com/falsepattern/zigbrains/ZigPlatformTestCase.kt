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

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl

// ponytail: copy of shared/src/test/kotlin/.../ZigPlatformTestCase.kt (module test sources aren't a Gradle
// artifact here); keep the two in sync until a testFixtures split makes one the source of truth.
/**
 * Headless platform test with the Zig plugin loaded and the project's content root on the real file system
 * (not the in-memory `temp:///` of [com.intellij.testFramework.fixtures.BasePlatformTestCase]).
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

    /** Creates `relativePath` under the content root and opens it in the editor. */
    protected fun configure(relativePath: String, text: String): PsiFile {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(relativePath, text).virtualFile)
        return myFixture.file
    }
}
