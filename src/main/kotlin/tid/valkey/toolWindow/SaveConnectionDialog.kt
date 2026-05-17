package tid.valkey.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Shows a save-connection dialog. Returns a pair of (name, rememberPassword) or null if cancelled.
 */
fun showSaveConnectionDialog(
    project: Project?,
    defaultName: String
): Pair<String, Boolean>? {
    val name = Messages.showInputDialog(
        project,
        tid.valkey.toolWindow.ValkeyBrowserPanel.message("valkey.browser.dialog.save.connection.prompt"),
        tid.valkey.toolWindow.ValkeyBrowserPanel.message("valkey.browser.dialog.save.connection.title"),
        Messages.getQuestionIcon(),
        defaultName,
        null
    ) ?: return null

    if (name.trim().isEmpty()) return null

    val rememberResult = Messages.showYesNoDialog(
        project,
        tid.valkey.toolWindow.ValkeyBrowserPanel.message("valkey.browser.dialog.save.connection.remember.prompt"),
        tid.valkey.toolWindow.ValkeyBrowserPanel.message("valkey.browser.dialog.save.connection.title"),
        Messages.getQuestionIcon()
    )

    return name.trim() to (rememberResult == Messages.YES)
}
