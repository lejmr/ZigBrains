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

package com.falsepattern.zigbrains.project.webui

import com.falsepattern.zigbrains.project.execution.base.ZigExecConfig
import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser

private const val TOOL_WINDOW_ID = "Zig Web UI"
private const val MAX_BUFFER = 4096
private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
private val URL_REGEX = Regex("""web interface listening at (http://\S+/)""")

/** Attaches [ZigWebUiProcessListener] to every Zig run/debug process, including the debugger's own handler. */
class ZigWebUiExecutionListener : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (env.runProfile !is ZigExecConfig<*>)
            return
        handler.addProcessListener(ZigWebUiProcessListener(env.project, env.runProfile.name))
    }
}

/** Watches a Zig run's PTY output for the `--webui`/`--fuzz` server URL and opens it once. */
class ZigWebUiProcessListener(private val project: Project, private val title: String) : ProcessListener {
    private var triggered = false
    private val buffer = StringBuilder()

    // PTY output arrives in arbitrary chunks, so only complete lines are matched
    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        if (triggered)
            return
        buffer.append(event.text)
        var newline = buffer.indexOf("\n")
        while (newline >= 0) {
            val line = ANSI_ESCAPE.replace(buffer.substring(0, newline), "")
            buffer.delete(0, newline + 1)
            val match = URL_REGEX.find(line)
            if (match != null) {
                triggered = true
                openWebUi(project, match.groupValues[1], title)
                return
            }
            newline = buffer.indexOf("\n")
        }
        if (buffer.length > MAX_BUFFER)
            buffer.clear()
    }
}

/** Shows the Zig build web UI: an embedded browser tab, or a notification with the link if JCEF is unavailable. */
fun openWebUi(project: Project, url: String, title: String) {
    if (!JBCefApp.isSupported()) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("zigbrains-webui")
            .createNotification(
                WebUiBundle.message("notification.title.webui-no-jcef"),
                WebUiBundle.message("notification.content.webui-no-jcef", url),
                NotificationType.INFORMATION
            )
            .notify(project)
        return
    }
    ApplicationManager.getApplication().invokeLater({
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return@invokeLater
        toolWindow.contentManager.findContent(title)?.let { toolWindow.contentManager.removeContent(it, true) }
        val browser = JBCefBrowser(url)
        val content = ContentFactory.getInstance().createContent(browser.component, title, false)
        content.setDisposer(browser)
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
        toolWindow.isAvailable = true
        toolWindow.show()
    }, project.disposed)
}

/** Registers the "Zig Web UI" tool window; content is added on demand by [openWebUi]. */
class ZigWebUiToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // content tabs are created per run by openWebUi(), nothing to build up front
    }

    override fun shouldBeAvailable(project: Project): Boolean = false
}
