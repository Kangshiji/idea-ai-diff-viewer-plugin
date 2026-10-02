package com.company.aidiff.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** 重新加载 patch */
class RefreshPatchAction(private val handler: () -> Unit) :
    AnAction("刷新", "重新加载 .ai_diff/ai-changes.patch", AllIcons.Actions.Refresh), DumbAware {

    override fun actionPerformed(e: AnActionEvent) = handler()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/** 接受文件列表中所有文件的改动 */
class AcceptAllFilesAction(private val handler: () -> Unit) :
    AnAction(
        "Accept All",
        "接受文件列表中所有文件的全部改动",
        AllIcons.Actions.Checked,
    ), DumbAware {

    override fun actionPerformed(e: AnActionEvent) = handler()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/** 拒绝文件列表中所有文件的改动 */
class RejectAllFilesAction(private val handler: () -> Unit) :
    AnAction(
        "Reject All",
        "拒绝文件列表中所有文件的全部改动，仅回滚 AI 的改动，保留用户自己的修改",
        AllIcons.Actions.Cancel,
    ), DumbAware {

    override fun actionPerformed(e: AnActionEvent) = handler()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
