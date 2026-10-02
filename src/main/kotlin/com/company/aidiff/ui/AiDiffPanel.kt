package com.company.aidiff.ui

import com.company.aidiff.actions.AcceptAllFilesAction
import com.company.aidiff.actions.RefreshPatchAction
import com.company.aidiff.actions.RejectAllFilesAction
import com.company.aidiff.model.FileChange
import com.company.aidiff.services.EditorReviewManager
import com.company.aidiff.services.PatchStateService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent

/**
 * AI Diff 工具窗口：
 *  - 顶部工具栏：刷新 / Reject All（全部文件）/ Accept All（全部文件）；
 *  - 文件列表每行末尾：勾（接受该文件）/ 叉（拒绝该文件）；
 *  - 双击行：在真实编辑器中打开文件。
 *
 * 行使用真实组件（JBList 的渲染器无法承载可点击按钮），状态变化时整体重建。
 */
class AiDiffPanel(private val project: Project) : JBPanel<JBPanel<*>>(BorderLayout()), Disposable {

    private val service = PatchStateService.getInstance(project)
    private val reviewManager = EditorReviewManager.getInstance(project)
    private val rowsPanel = JBPanel<JBPanel<*>>().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
    }
    private val statusLabel = JBLabel(" ")
    private val emptyLabel = JBLabel(" ", JBLabel.CENTER).apply {
        border = JBUI.Borders.empty(20)
    }
    private val listCards = java.awt.CardLayout()
    private val listContainer = JBPanel<JBPanel<*>>(listCards)

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var lastKnownStamp: Long = -2L

    init {
        border = JBUI.Borders.empty(4)
        add(buildToolbar().component, BorderLayout.NORTH)

        listContainer.add(
            ScrollPaneFactory.createScrollPane(rowsPanel, true),
            CARD_ROWS,
        )
        listContainer.add(emptyLabel, CARD_EMPTY)
        add(listContainer, BorderLayout.CENTER)

        statusLabel.border = JBUI.Borders.empty(4, 4, 0, 0)
        add(statusLabel, BorderLayout.SOUTH)

        project.messageBus.connect(this).subscribe(
            PatchStateService.TOPIC,
            PatchStateService.StateListener { refreshUi() },
        )

        schedulePoll()
        service.loadState()
        service.reload()
    }

    private fun buildToolbar(): com.intellij.openapi.actionSystem.ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(RefreshPatchAction { manualRefresh() })
            addSeparator()
            add(AcceptAllFilesAction { acceptAllFiles() })
            add(RejectAllFilesAction { rejectAllFiles() })
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("AiDiffToolbar", group, true)
        toolbar.targetComponent = rowsPanel
        return toolbar
    }

    // ---------------- 列表刷新 ----------------

    private fun refreshUi() {
        rowsPanel.removeAll()
        for (change in service.changes) {
            rowsPanel.add(buildRow(change))
        }

        val isEmpty = service.changes.isEmpty()
        if (!isEmpty) {
            listCards.show(listContainer, CARD_ROWS)
        } else {
            emptyLabel.text = when {
                service.lastError != null -> service.lastError
                !service.patchExists ->
                    "未找到 ${PatchStateService.DIFF_DIR}/${PatchStateService.PATCH_FILE_NAME}，等待 AI 工具生成…"
                else -> "暂无待确认变更"
            }
            listCards.show(listContainer, CARD_EMPTY)
        }

        statusLabel.text = when {
            service.lastError != null -> service.lastError
            !service.patchExists -> "等待 AI 工具生成 patch…"
            service.changes.isEmpty() -> "Patch 为空，暂无待确认变更"
            else -> {
                val driftedCount = service.changes.count { service.isDrifted(it.path) }
                val driftText = if (driftedCount > 0) "，$driftedCount 个文件存在冲突" else ""
                "${service.changes.size} 个文件待确认$driftText"
            }
        }

        rowsPanel.revalidate()
        rowsPanel.repaint()
    }

    /** 构造一行：信息文本 + 勾/叉按钮 */
    private fun buildRow(change: FileChange): JComponent {
        val row = object : JBPanel<JBPanel<*>>(BorderLayout()) {
            init {
                border = JBUI.Borders.empty(2, 4)
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, ROW_HEIGHT)
                preferredSize = java.awt.Dimension(preferredSize.width, ROW_HEIGHT)
            }
        }

        val conflict = if (service.isDrifted(change.path))
            "<font color='#C62828'>[冲突]</font> " else ""
        val info = JBLabel(
            "<html>$conflict${change.path} " +
                "<font color='#2E7D32'>+${change.addedLines}</font> " +
                "<font color='#C62828'>-${change.deletedLines}</font> " +
                "<font color='#808080'>[${change.blocks.size}块]</font></html>",
        )
        row.add(info, BorderLayout.CENTER)

        val acceptButton = smallIconButton(
            AllIcons.Actions.Checked,
            "接受该文件的全部改动",
        ) { acceptOneFile(change) }
        val rejectButton = smallIconButton(
            AllIcons.Actions.Cancel,
            "拒绝该文件的全部改动",
        ) { rejectOneFile(change) }

        val buttons = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false
            add(acceptButton)
            add(rejectButton)
        }
        row.add(buttons, BorderLayout.EAST)

        // 悬停高亮 + 双击打开 + 右键菜单
        val hover = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                row.background = ROW_HOVER_COLOR
                row.isOpaque = true
            }

            override fun mouseExited(e: MouseEvent) {
                row.isOpaque = false
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    reviewManager.openAndNavigate(change)
                }
            }

            override fun mousePressed(e: MouseEvent) = maybePopup(e, change)
            override fun mouseReleased(e: MouseEvent) = maybePopup(e, change)
        }
        row.addMouseListener(hover)
        info.addMouseListener(hover)
        return row
    }

    private fun smallIconButton(
        icon: javax.swing.Icon,
        tip: String,
        onClick: () -> Unit,
    ): JButton = JButton(icon).apply {
        toolTipText = tip
        preferredSize = java.awt.Dimension(BUTTON_SIZE, BUTTON_SIZE)
        minimumSize = preferredSize
        maximumSize = preferredSize
        isContentAreaFilled = false
        border = JBUI.Borders.empty(1)
        isFocusable = false
        addActionListener { onClick() }
    }

    // ---------------- 单文件 Accept / Reject ----------------

    private fun acceptOneFile(change: FileChange) {
        service.acceptFile(change)?.let {
            Messages.showWarningDialog(project, it, "AI Diff")
        }
    }

    private fun rejectOneFile(change: FileChange) {
        val answer = Messages.showYesNoDialog(
            project,
            "将拒绝 ${change.path} 的全部改动（仅回滚 AI 改动，保留你自己的修改）。\n是否继续？",
            "Reject File",
            "回滚",
            "取消",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        ApplicationManager.getApplication().invokeLater {
            service.rejectFile(change)?.let {
                Messages.showWarningDialog(project, it, "AI Diff")
            }
        }
    }

    // ---------------- 全部文件 Accept All / Reject All ----------------

    private fun acceptAllFiles() {
        if (service.changes.isEmpty()) {
            Messages.showInfoMessage(project, "当前没有待确认的文件。", "AI Diff")
            return
        }
        service.acceptAllFiles()?.let {
            Messages.showWarningDialog(project, it, "AI Diff")
        }
    }

    private fun rejectAllFiles() {
        if (service.changes.isEmpty()) {
            Messages.showInfoMessage(project, "当前没有待确认的文件。", "AI Diff")
            return
        }
        val count = service.changes.size
        val answer = Messages.showYesNoDialog(
            project,
            "将拒绝全部 $count 个文件的 AI 改动（保留你自己的修改）。\n是否继续？",
            "Reject All Files",
            "回滚",
            "取消",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        ApplicationManager.getApplication().invokeLater {
            service.rejectAllFiles()?.let {
                Messages.showWarningDialog(project, it, "AI Diff")
            }
        }
    }

    /** 行右键菜单：打开 / 接受 / 拒绝该文件 */
    private fun maybePopup(e: MouseEvent, change: FileChange) {
        if (!e.isPopupTrigger) return
        val openAction = object : com.intellij.openapi.actionSystem.AnAction("在编辑器中打开") {
            override fun actionPerformed(ev: AnActionEvent) {
                reviewManager.openAndNavigate(change)
            }
        }
        val acceptAction = object : com.intellij.openapi.actionSystem.AnAction(
            "Accept（该文件）", null, AllIcons.Actions.Checked,
        ) {
            override fun actionPerformed(ev: AnActionEvent) = acceptOneFile(change)
        }
        val rejectAction = object : com.intellij.openapi.actionSystem.AnAction(
            "Reject（该文件）", null, AllIcons.Actions.Cancel,
        ) {
            override fun actionPerformed(ev: AnActionEvent) = rejectOneFile(change)
        }
        val group = DefaultActionGroup().apply {
            add(openAction)
            addSeparator()
            add(acceptAction)
            add(rejectAction)
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null, group, com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
            )
            .show(com.intellij.ui.awt.RelativePoint(e))
    }

    // ---------------- 手动刷新与轮询 ----------------

    private fun manualRefresh() {
        service.patchIoFile()?.let { ioFile ->
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
        }
        lastKnownStamp = service.patchStamp()
        service.reload()
    }

    private fun schedulePoll() {
        alarm.addRequest({
            pollPatchFile()
            schedulePoll()
        }, 2000)
    }

    private fun pollPatchFile() {
        if (project.isDisposed) return
        val stamp = service.patchStamp()
        if (stamp != lastKnownStamp) {
            lastKnownStamp = stamp
            manualRefresh()
        }
    }

    override fun dispose() {
        // Alarm、messageBus 均以 this 为 parent disposable，自动释放
    }

    companion object {
        private const val CARD_ROWS = "rows"
        private const val CARD_EMPTY = "empty"
        private const val ROW_HEIGHT = 26
        private const val BUTTON_SIZE = 20

        private val ROW_HOVER_COLOR = JBColor(
            java.awt.Color(0xF0F4FA),
            java.awt.Color(0x333942),
        )
    }
}
