package com.company.aidiff.services

import com.company.aidiff.model.FileChange
import com.company.aidiff.model.ReviewBlock
import com.company.aidiff.ui.DeletedLinesInlayRenderer
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseWheelListener
import javax.swing.JButton

/**
 * 把审查叠加层直接应用到用户日常打开的真实编辑器上。
 *
 * 位置锚定：块的 RangeMarker 由 [PatchStateService] 在 Document 上统一维护（分屏共享），
 * 块外编辑时 marker 自动跟随，红绿叠加不受影响；本管理器只负责编辑器级渲染：
 *  - 绿色新增行：区间高亮（整行铺满），自身也随文档变更跟踪；
 *  - 红色删除行：Block Inlay，不写入 Document，不影响编译运行。
 *
 * 同步：fileOpened 事件 + 状态 TOPIC + Alarm 兜底 reconcile，覆盖分屏/晚打开场景。
 */
@Service(Service.Level.PROJECT)
class EditorReviewManager(private val project: Project) {

    private val service = PatchStateService.getInstance(project)
    private val controllers = HashMap<Editor, Controller>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)

    init {
        val bus = project.messageBus.connect()

        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
                reconcile()
            }
        })

        bus.subscribe(PatchStateService.TOPIC, PatchStateService.StateListener { reconcile() })

        // 全局悬停：只对被管控编辑器响应
        com.intellij.openapi.editor.EditorFactory.getInstance().eventMulticaster
            .addEditorMouseMotionListener(object : EditorMouseMotionListener {
                override fun mouseMoved(e: EditorMouseEvent) {
                    onHover(e)
                }
            }, project)

        // 用户编辑：转发给 Service 判定（块内编辑自动 Accept，块外忽略）
        com.intellij.openapi.editor.EditorFactory.getInstance().eventMulticaster
            .addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        .getFile(event.document) ?: return
                    val rel = relativePath(file) ?: return
                    if (service.findChange(rel) != null) {
                        service.onUserDocumentChanged(rel, event)
                    }
                }
            }, project)

        scheduleReconcile()
    }

    companion object {
        private val LOG = thisLogger()
        private const val RECONCILE_DELAY = 1500

        private val GREEN_ATTRIBUTES = TextAttributes().apply {
            backgroundColor = JBColor(Color(0xE5F8E5), Color(0x2A402C))
        }

        fun getInstance(project: Project): EditorReviewManager =
            project.getService(EditorReviewManager::class.java)
    }

    // ---------------- 定位 ----------------

    private fun baseDir(): com.intellij.openapi.vfs.VirtualFile? {
        val path = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath(path)
    }

    private fun relativePath(file: com.intellij.openapi.vfs.VirtualFile): String? {
        val base = baseDir() ?: return null
        return VfsUtilCore.getRelativePath(file, base, '/')
    }

    /** 打开真实文件（或选中已有 tab），滚动到第一个未决块 */
    fun openAndNavigate(change: FileChange) {
        val base = baseDir()
        val file = base?.findFileByRelativePath(change.path)
        if (file == null) {
            Messages.showInfoMessage(
                project,
                "文件 ${change.path} 当前不存在（可能已被 AI 删除）。\n" +
                    "如需恢复原文件，请使用 Reject All。",
                "AI Diff",
            )
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true)
        reconcile()

        val editor = getEditorsFor(file).firstOrNull() ?: return
        val anchors = service.anchorsFor(change)
        val firstAnchor = change.undecidedBlocks().firstOrNull()?.let { b ->
            anchors.firstOrNull { it.block === b && it.valid }
        } ?: return

        val targetOffset = firstAnchor.start
        IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
        editor.caretModel.moveToOffset(targetOffset)
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
    }

    private fun getEditorsFor(file: com.intellij.openapi.vfs.VirtualFile): List<Editor> =
        FileEditorManager.getInstance(project).getAllEditors(file)
            .filterIsInstance<TextEditor>().map { it.editor }

    // ---------------- Reconcile ----------------

    private fun scheduleReconcile() {
        alarm.addRequest({
            reconcile()
            scheduleReconcile()
        }, RECONCILE_DELAY)
    }

    private fun reconcile() {
        if (project.isDisposed) return

        val activeEditors = HashSet<Editor>()
        for (change in service.changes) {
            val file = baseDir()?.findFileByRelativePath(change.path) ?: continue
            for (editor in getEditorsFor(file)) {
                activeEditors.add(editor)
                val controller = controllers.getOrPut(editor) { Controller(editor, service) }
                controller.update(change)
            }
        }

        val stale = controllers.keys.filterNot { activeEditors.contains(it) }
        for (editor in stale) {
            controllers.remove(editor)?.disposeOverlays()
        }
    }

    // ---------------- 悬停 ----------------

    private fun onHover(e: EditorMouseEvent) {
        val controller = controllers[e.editor] ?: return
        val point: Point = e.mouseEvent.point

        var block: ReviewBlock? = controller.blockFromInlay(point)
        if (block == null) {
            val logicalLine = e.editor.xyToLogicalPosition(point).line
            block = controller.blockAtLine(logicalLine)
        }
        if (block != null && !block.isUndecided) block = null

        if (block === controller.hoveredBlock) return
        controller.hoveredBlock = block

        hidePopup(controller)
        if (block == null) {
            e.editor.contentComponent.cursor = Cursor.getDefaultCursor()
        } else {
            e.editor.contentComponent.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            showBlockPopup(controller, block, point)
        }
    }

    private fun showBlockPopup(controller: Controller, block: ReviewBlock, point: Point) {
        val change = controller.change ?: return

        val acceptButton = blockActionButton("Accept", Color(0x2E7D32), "保留此块的新增代码") {
            hidePopup(controller)
            service.acceptBlock(change, block)?.let {
                Messages.showWarningDialog(project, it, "AI Diff")
            }
        }
        val rejectButton = blockActionButton("Reject", Color(0xC62828), "撤销此块，恢复删除的代码") {
            hidePopup(controller)
            service.rejectBlock(change, block)?.let {
                Messages.showWarningDialog(project, it, "AI Diff")
            }
        }

        val panel = com.intellij.ui.components.JBPanel<com.intellij.ui.components.JBPanel<*>>().apply {
            border = JBUI.Borders.empty(2)
            add(acceptButton)
            add(rejectButton)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setFocusable(false)
            .setShowShadow(true)
            .setCancelOnClickOutside(false)
            .createPopup()
        controller.popup = popup

        val wheelListener = MouseWheelListener { hidePopup(controller) }
        controller.wheelListener = wheelListener
        controller.editor.contentComponent.addMouseWheelListener(wheelListener)

        popup.show(RelativePoint(controller.editor.contentComponent, point))
    }

    private fun hidePopup(controller: Controller) {
        controller.popup?.cancel()
        controller.popup = null
        controller.wheelListener?.let {
            controller.editor.contentComponent.removeMouseWheelListener(it)
        }
        controller.wheelListener = null
        controller.hoveredBlock = null
    }

    private fun blockActionButton(text: String, textColor: Color, tip: String, onClick: () -> Unit): JButton =
        JButton(text).apply {
            toolTipText = tip
            foreground = textColor
            font = font.deriveFont(Font.BOLD)
            addActionListener { onClick() }
        }

    // ---------------- 单编辑器控制器 ----------------

    private class Controller(
        val editor: Editor,
        val service: PatchStateService,
    ) {
        var change: FileChange? = null
        var hoveredBlock: ReviewBlock? = null
        var popup: JBPopup? = null
        var wheelListener: MouseWheelListener? = null

        private val overlays = mutableListOf<com.intellij.openapi.Disposable>()
        private val inlayByBlock = HashMap<ReviewBlock, com.intellij.openapi.editor.Inlay<*>>()
        private var signature: Int? = null

        fun update(newChange: FileChange) {
            // 触发 Service 建立/校验锚点；冲突文件返回空锚点
            val anchors = service.anchorsFor(newChange)
            val newSignature = computeSignature(newChange, anchors)
            if (newSignature == signature) return
            signature = newSignature
            change = newChange
            disposeOverlays()

            for (anchor in anchors) {
                val block = anchor.block
                if (!block.isUndecided || !anchor.valid) continue

                // 绿色新增行：整行铺满的区间高亮（高亮自身跟踪文档变更）
                if (anchor.end > anchor.start) {
                    // 锚点终点是「下一新增行/下一行」的起始偏移（Reject 需整行
                    // 含换行符一起替换）。LINES_IN_RANGE 会把终点所在行也染色，
                    // 因此高亮终点回退到换行符位置，只覆盖真实新增行。
                    val highlightEnd = (anchor.end - 1).coerceAtLeast(anchor.start)
                    val highlighter = editor.markupModel.addRangeHighlighter(
                        anchor.start,
                        highlightEnd,
                        com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION - 1,
                        GREEN_ATTRIBUTES,
                        HighlighterTargetArea.LINES_IN_RANGE,
                    )
                    overlays.add(com.intellij.openapi.Disposable {
                        editor.markupModel.removeHighlighter(highlighter)
                    })
                }

                // 红色删除行：Block Inlay（虚拟显示，不进 Document）
                if (block.removedLines.isNotEmpty()) {
                    val inlay = editor.inlayModel.addBlockElement(
                        anchor.start,
                        /* relatesToPrecedingText */ false,
                        /* showAbove */ true,
                        /* priority */ 0,
                        DeletedLinesInlayRenderer(block.removedLines),
                    ) ?: continue
                    inlayByBlock[block] = inlay
                    overlays.add(inlay)
                }
            }
        }

        fun blockAtLine(line: Int): ReviewBlock? {
            val doc = editor.document
            if (line < 0 || line >= doc.lineCount) return null
            val offset = doc.getLineStartOffset(line)
            val anchors = service.anchorsFor(change ?: return null)
            return anchors.firstOrNull { a ->
                a.valid && offset >= a.start && offset < a.end
            }?.block
        }

        fun blockFromInlay(point: Point): ReviewBlock? {
            for ((block, inlay) in inlayByBlock) {
                val bounds = inlay.bounds ?: continue
                if (bounds.contains(point)) return block
            }
            return null
        }

        fun disposeOverlays() {
            overlays.forEach { Disposer.dispose(it) }
            overlays.clear()
            inlayByBlock.clear()
            popup?.cancel()
            popup = null
            wheelListener?.let { editor.contentComponent.removeMouseWheelListener(it) }
            wheelListener = null
            hoveredBlock = null
            signature = null
        }

        private fun computeSignature(change: FileChange, anchors: List<PatchStateService.BlockAnchor>): Int {
            var result = change.path.hashCode()
            result = 31 * result + change.blocks.joinToString(",") { it.state.name }.hashCode()
            result = 31 * result + anchors.joinToString(",") { "${it.start}-${it.end}" }.hashCode()
            return result
        }
    }
}
