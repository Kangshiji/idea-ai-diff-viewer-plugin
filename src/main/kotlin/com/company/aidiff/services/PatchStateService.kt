package com.company.aidiff.services

import com.company.aidiff.model.BlockState
import com.company.aidiff.model.ChangeType
import com.company.aidiff.model.FileChange
import com.company.aidiff.model.ReviewBlock
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.diff.impl.patch.PatchSyntaxException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.Topic
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 项目级状态服务：patch 加载/解析、块级与文件级 Accept/Reject、resolved 状态持久化。
 *
 * 支持「边审边改」：
 *  - 每个未决块在真实 Document 上有 RangeMarker 锚点，块外编辑（无关代码）完全不影响审查；
 *  - 用户在块内部（绿色新增行）直接编辑 → 该块自动视为 Accept；
 *  - Reject 是外科手术式局部替换，只动目标块，用户其他修改一律保留；
 *  - 仅保留一种冲突：锚点建立前文件已被改得无法与 patch 对应（如在插件加载前增删过行）。
 */
@Service(Service.Level.PROJECT)
class PatchStateService(private val project: Project) {

    private val lock = Any()

    @Volatile
    var changes: List<FileChange> = emptyList()
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var patchExists: Boolean = false
        private set

    /** 已处理完的文件路径（接受或拒绝都算），patch hash 一致时不再次展示 */
    private val resolvedPaths = linkedSetOf<String>()

    /** 锚点建立前内容就无法对应 patch 的路径（窄义冲突） */
    private val conflictedPaths = linkedSetOf<String>()
    private var persistedPatchHash: Int? = null

    /** 每个文件的块锚点（Document 级，分屏共享） */
    private val anchorsByPath = LinkedHashMap<String, MutableList<BlockAnchor>>()

    /** 用户决策落盘（插件 → AI CLI 的反馈通道） */
    private val decisionStore = DecisionStore(project)

    /** 正在执行插件自己的回滚写入，Document 监听据此跳过自动接受逻辑 */
    @Volatile
    private var applyingReviewEdit = false

    /** 块锚点：RangeMarker 随编辑自动跟随 */
    class BlockAnchor(val block: ReviewBlock, val marker: com.intellij.openapi.editor.RangeMarker) {
        val start: Int get() = marker.startOffset
        val end: Int get() = marker.endOffset
        val valid: Boolean get() = marker.isValid
    }

    init {
        registerVfsWatcher()
    }

    companion object {
        private val LOG = thisLogger()

        const val DIFF_DIR = ".ai_diff"
        const val PATCH_FILE_NAME = "ai-changes.patch"
        const val STATE_FILE_NAME = "state.txt"

        val TOPIC: Topic<StateListener> = Topic.create("AiDiffStateChanged", StateListener::class.java)

        fun getInstance(project: Project): PatchStateService =
            project.getService(PatchStateService::class.java)
    }

    fun interface StateListener {
        fun stateChanged()
    }

    fun patchIoFile(): File? {
        val base = project.basePath ?: return null
        return File(File(base, DIFF_DIR), PATCH_FILE_NAME)
    }

    fun patchStamp(): Long = patchIoFile()?.let { if (it.isFile) it.lastModified() else -1L } ?: -1L

    fun findChange(path: String): FileChange? = changes.firstOrNull { it.path == path }

    fun isDrifted(path: String): Boolean = conflictedPaths.contains(path)

    // ---------------- 加载与解析 ----------------

    fun reload() {
        val patchFile = patchIoFile() ?: run {
            updateState(emptyList(), exists = false, error = "无法获取项目根路径")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!patchFile.isFile) {
                updateState(emptyList(), exists = false, error = null)
                return@executeOnPooledThread
            }
            try {
                val text = patchFile.readText(StandardCharsets.UTF_8)
                val parsed = PatchParser.parse(text)
                val hash = text.hashCode()
                synchronized(lock) {
                    if (persistedPatchHash != null && persistedPatchHash != hash) {
                        resolvedPaths.clear()
                        persistedPatchHash = null
                    }
                }

                // 按 decisions.jsonl 恢复块状态：刷新会重建数据对象，
                // 不恢复则已接受/已拒绝的块会重新以未决状态出现。
                // 指纹只依赖块内容，因此即使 patch 内容变化，相同块的决策依然生效。
                val decisionsFile = File(File(project.basePath, DIFF_DIR),
                                         DecisionStore.DECISIONS_FILE_NAME)
                val decisions = DecisionStore.loadDecisions(decisionsFile)
                for (change in parsed) {
                    val perFile = decisions[change.path] ?: continue
                    for (block in change.blocks) {
                        when (perFile[DecisionStore.fingerprintOf(block.removedLines, block.addedLines)]) {
                            "accepted" -> block.state = BlockState.ACCEPTED
                            "rejected" -> block.state = BlockState.REJECTED
                        }
                    }
                    if (change.allBlocksDecided()) {
                        synchronized(lock) { resolvedPaths.add(change.path) }
                    }
                }

                conflictedPaths.clear()
                disposeAllAnchors()
                val visible = parsed.filterNot { resolvedPaths.contains(it.path) }
                updateState(visible, exists = true, error = null)
                persistState()
            } catch (e: PatchSyntaxException) {
                LOG.warn("Patch 解析失败: ${e.message}")
                updateState(emptyList(), exists = true, error = "Patch 解析失败: ${e.message}")
            } catch (e: Exception) {
                LOG.warn("Patch 读取失败", e)
                updateState(emptyList(), exists = true, error = "Patch 读取失败: ${e.message}")
            }
        }
    }

    private fun updateState(newChanges: List<FileChange>, exists: Boolean, error: String?) {
        changes = newChanges
        patchExists = exists
        lastError = error
        ApplicationManager.getApplication().invokeLater(
            { project.messageBus.syncPublisher(TOPIC).stateChanged() },
            ModalityState.any(),
            project.disposed,
        )
    }

    // ---------------- 文件级 Accept All / Reject All ----------------

    /** Accept All：全部块标记接受，文件不改（含用户自己的修改也保留），移出列表 */
    fun acceptFile(change: FileChange): String? {
        if (conflictedPaths.contains(change.path)) return conflictMessage(change.path)
        change.blocks.forEach {
            it.state = BlockState.ACCEPTED
            decisionStore.record(it, accepted = true)
        }
        change.accepted = true
        disposeAnchors(change.path)
        finalizeFile(change)
        return null
    }

    /**
     * 接受文件列表中所有文件的改动。
     * @return 错误信息（冲突文件的说明）；全部成功返回 null
     */
    fun acceptAllFiles(): String? {
        val snapshot = changes.toList()
        val errors = snapshot.mapNotNull { acceptFile(it) }
        return errors.joinToString("\n").ifEmpty { null }
    }

    /**
     * 拒绝文件列表中所有文件的改动（逐块局部回滚，保留用户自己的修改）。
     * @return 错误信息；全部成功返回 null
     */
    fun rejectAllFiles(): String? {
        val snapshot = changes.toList()
        val errors = snapshot.mapNotNull { rejectFile(it) }
        return errors.joinToString("\n").ifEmpty { null }
    }

    /**
     * Reject All：逐块外科手术式回滚，只撤销 AI 的改动，用户自己的修改保留。
     * 被整体删除的文件：全文重建恢复；全新文件：回滚后删除。
     */
    fun rejectFile(change: FileChange): String? {
        if (conflictedPaths.contains(change.path)) return conflictMessage(change.path)

        val document = resolveDocument(change)
        if (document == null) {
            // 文件不存在（DELETED 类型）：重建恢复
            val baseline = PatchParser.renderBaselineText(change)
            try {
                createFileWithText(change, baseline)
            } catch (e: Exception) {
                LOG.warn("文件恢复失败: ${change.path}", e)
                return "文件恢复失败: ${e.message}"
            }
            change.blocks.forEach {
                it.state = BlockState.REJECTED
                decisionStore.record(it, accepted = false)
            }
            finalizeFile(change)
            return null
        }

        val anchors = ensureAnchors(change)
        if (conflictedPaths.contains(change.path)) return conflictMessage(change.path)
        val vFile = FileDocumentManager.getInstance().getFile(document)

        try {
            applyingReviewEdit = true
            WriteCommandAction.writeCommandAction(project).withName("AI Reject All").run<RuntimeException> {
                // 自下而上逐块替换，避免上方块偏移
                for (anchor in anchors.sortedByDescending { it.start }) {
                    document.replaceString(
                        anchor.start, anchor.end,
                        removedReplacement(change, anchor.block),
                    )
                }
                // 全新文件回滚后应为空 → 删除文件
                if (change.changeType == ChangeType.ADDED && document.textLength == 0 && vFile != null) {
                    vFile.delete(this)
                }
            }
        } catch (e: Exception) {
            LOG.warn("Reject All 失败: ${change.path}", e)
            return "Reject All 失败: ${e.message}"
        } finally {
            applyingReviewEdit = false
        }

        change.blocks.forEach {
            it.state = BlockState.REJECTED
            decisionStore.record(it, accepted = false)
        }
        disposeAnchors(change.path)
        finalizeFile(change)
        return null
    }

    // ---------------- 块级 Accept / Reject ----------------

    /** Accept 单个块：不改磁盘（新增行本来就在），可能触发文件完成 */
    fun acceptBlock(change: FileChange, block: ReviewBlock): String? {
        if (conflictedPaths.contains(change.path)) return conflictMessage(change.path)
        block.state = BlockState.ACCEPTED
        decisionStore.record(block, accepted = true)
        disposeAnchor(change.path, block)
        if (change.allBlocksDecided()) {
            change.accepted = change.blocks.all { it.state == BlockState.ACCEPTED }
            finalizeFile(change)
        } else {
            notifyChanged()
        }
        return null
    }

    /**
     * Reject 单个块：仅外科手术式替换目标块区域，文件其余位置（含用户其他修改）不动。
     */
    fun rejectBlock(change: FileChange, block: ReviewBlock): String? {
        if (conflictedPaths.contains(change.path)) return conflictMessage(change.path)

        val document = resolveDocument(change)
            ?: return "文件 ${change.path} 不存在，无法局部回滚，请改用 Reject All 恢复。"
        val anchors = ensureAnchors(change)
        if (conflictedPaths.contains(change.path)) return conflictMessage(change.path)
        val anchor = anchors.firstOrNull { it.block === block && it.valid }
            ?: return "未找到该块的锚点，请刷新后重试。"

        val replacement = removedReplacement(change, block)
        try {
            applyingReviewEdit = true
            WriteCommandAction.writeCommandAction(project).withName("AI Reject Block").run<RuntimeException> {
                document.replaceString(anchor.start, anchor.end, replacement)
            }
        } catch (e: Exception) {
            LOG.warn("块回滚失败: ${change.path}", e)
            return "块回滚失败: ${e.message}"
        } finally {
            applyingReviewEdit = false
        }

        block.state = BlockState.REJECTED
        decisionStore.record(block, accepted = false)
        disposeAnchor(change.path, block)

        if (change.allBlocksDecided()) {
            change.accepted = change.blocks.all { it.state == BlockState.ACCEPTED }
            finalizeFile(change)
        } else {
            notifyChanged()
        }
        return null
    }

    // ---------------- 用户编辑事件（由 EditorReviewManager 转发） ----------------

    /**
     * 用户在已锚定的文件中输入：
     *  - 改动落在块区域内（含零宽锚点处插入）→ 自动接受该块；
     *  - 块外编辑 → 忽略，marker 自动跟随。
     */
    fun onUserDocumentChanged(path: String, event: com.intellij.openapi.editor.event.DocumentEvent) {
        if (applyingReviewEdit) return
        val anchors = anchorsByPath[path] ?: return
        val hit = anchors.firstOrNull { a ->
            val m = a.marker
            when {
                !m.isValid -> false
                m.startOffset == m.endOffset ->
                    // 零宽锚点（纯删除块）：在锚点位置插入即视为手改
                    event.newLength > 0 && event.offset == m.startOffset
                else ->
                    event.offset < m.endOffset && event.offset + event.oldLength > m.startOffset
            }
        }
        if (hit != null) {
            val change = findChange(path) ?: return
            acceptBlock(change, hit.block)
        }
    }

    // ---------------- 锚点建立 ----------------

    fun anchorsFor(change: FileChange): List<BlockAnchor> {
        ensureAnchors(change)
        return anchorsByPath[change.path].orEmpty()
    }

    /**
     * 为文件的未决块建立锚点。文件缺失返回空列表（调用方按"已删除"处理）。
     *
     * 刷新后数据对象重建、块可能已带决策：
     *  - ACCEPTED：磁盘保持新增行，不建锚点，不影响后续行号；
     *  - REJECTED：磁盘上是删除行，后续块相对 patch 记录位置位移
     *    (removedLines.size - addedLines.size)，据此修正；
     *  - UNDECIDED：在修正后的位置校验新增行内容并建锚点，内容对不上则标冲突。
     */
    private fun ensureAnchors(change: FileChange): List<BlockAnchor> {
        anchorsByPath[change.path]?.let { return it }

        val document = resolveDocument(change) ?: return emptyList()

        val anchors = mutableListOf<BlockAnchor>()
        var shift = 0
        for (block in change.blocks) {
            when (block.state) {
                BlockState.ACCEPTED -> Unit
                BlockState.REJECTED ->
                    shift += block.removedLines.size - block.addedLines.size
                BlockState.UNDECIDED -> {
                    val startLine = block.afterStartLine + shift

                    for (j in block.addedLines.indices) {
                        val line = startLine + j
                        if (line >= document.lineCount ||
                            document.getText(
                                com.intellij.openapi.util.TextRange(
                                    document.getLineStartOffset(line),
                                    document.getLineEndOffset(line),
                                ),
                            ) != block.addedLines[j]
                        ) {
                            markConflict(change)
                            return emptyList()
                        }
                    }

                    val pos = if (startLine < document.lineCount)
                        document.getLineStartOffset(startLine)
                    else document.textLength

                    val end = if (block.addedLines.isNotEmpty()) {
                        val lastAdded = startLine + block.addedLines.size - 1
                        if (lastAdded + 1 < document.lineCount)
                            document.getLineStartOffset(lastAdded + 1)
                        else document.textLength
                    } else pos

                    val marker = document.createRangeMarker(pos, end)
                    anchors.add(BlockAnchor(block, marker))
                }
            }
        }

        anchorsByPath[change.path] = anchors
        return anchors
    }

    private fun markConflict(change: FileChange) {
        conflictedPaths.add(change.path)
        disposeAnchors(change.path)
        notifyChanged()
    }

    /**
     * Reject 后替换进文件的文本：删除行按基线重建；
     * 仅当删除行处于基线文件末尾且基线无末尾换行时不加换行，其余行均带换行。
     */
    private fun removedReplacement(change: FileChange, block: ReviewBlock): String {
        if (block.removedLines.isEmpty()) return ""
        val atEnd = block.beforeStartLine + block.removedLines.size == change.beforeTotalLines
        val trailingNewline = !atEnd || change.beforeEndsWithNewline
        return block.removedLines.joinToString("\n") + if (trailingNewline) "\n" else ""
    }

    private fun resolveDocument(change: FileChange): com.intellij.openapi.editor.Document? {
        val base = project.basePath ?: return null
        val absPath = File(base, change.path).path
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath) ?: return null
        return FileDocumentManager.getInstance().getDocument(vFile)
    }

    // ---------------- 文件创建（已删除文件恢复） ----------------

    private fun createFileWithText(change: FileChange, text: String) {
        val base = project.basePath ?: throw IllegalStateException("无项目根路径")
        val absPath = File(base, change.path)
        WriteCommandAction.writeCommandAction(project).withName("AI File Restore").run<RuntimeException> {
            val parentPath = absPath.parent?.replace('\\', '/')
                ?: throw IllegalStateException("路径无父目录")
            val parent = VfsUtil.createDirectoryIfMissing(parentPath)
                ?: throw IllegalStateException("无法创建目录: $parentPath")
            val created = parent.createChildData(this, absPath.name)
            VfsUtil.saveText(created, text)
        }
    }

    // ---------------- 完成与持久化 ----------------

    private fun finalizeFile(change: FileChange) {
        synchronized(lock) {
            resolvedPaths.add(change.path)
        }
        changes = changes.filterNot { it.path == change.path }
        persistState()
        notifyChanged()
    }

    private fun stateIoFile(): File? {
        val base = project.basePath ?: return null
        return File(File(base, DIFF_DIR), STATE_FILE_NAME)
    }

    fun loadState() {
        val patchFile = patchIoFile() ?: return
        val stateFile = stateIoFile() ?: return
        if (!patchFile.isFile || !stateFile.isFile) return
        try {
            val hash = patchFile.readText(StandardCharsets.UTF_8).hashCode()
            val lines = stateFile.readLines(StandardCharsets.UTF_8)
            val savedHash = lines.firstOrNull { it.startsWith("hash=") }
                ?.removePrefix("hash=")?.toIntOrNull()
            synchronized(lock) {
                persistedPatchHash = savedHash
                if (savedHash != null && savedHash == hash) {
                    resolvedPaths.clear()
                    lines.filter { it.startsWith("resolved=") }
                        .forEach { resolvedPaths.add(it.removePrefix("resolved=")) }
                }
            }
        } catch (e: Exception) {
            LOG.warn("状态文件读取失败", e)
        }
    }

    private fun persistState() {
        val patchFile = patchIoFile() ?: return
        val stateFile = stateIoFile() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (!patchFile.isFile) return@executeOnPooledThread
                val hash = patchFile.readText(StandardCharsets.UTF_8).hashCode()
                val sb = StringBuilder("hash=").append(hash).append('\n')
                synchronized(lock) {
                    persistedPatchHash = hash
                    resolvedPaths.forEach { sb.append("resolved=").append(it).append('\n') }
                }
                stateFile.parentFile?.mkdirs()
                stateFile.writeText(sb.toString(), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                LOG.warn("状态文件写入失败", e)
            }
        }
    }

    // ---------------- 锚点释放 ----------------

    private fun disposeAnchor(path: String, block: ReviewBlock) {
        val list = anchorsByPath[path] ?: return
        val anchor = list.firstOrNull { it.block === block } ?: return
        list.remove(anchor)
        anchor.marker.dispose()
    }

    private fun disposeAnchors(path: String) {
        anchorsByPath.remove(path)?.forEach { it.marker.dispose() }
    }

    private fun disposeAllAnchors() {
        anchorsByPath.values.flatten().forEach { it.marker.dispose() }
        anchorsByPath.clear()
    }

    private fun conflictMessage(path: String) =
        "文件内容在审查前已与 patch 不一致，已跳过：$path（可删除该 patch 条目或刷新后重试）"

    // ---------------- 文件变更监听 ----------------

    private fun registerVfsWatcher() {
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val hit = events.any { e ->
                    e.path.replace('\\', '/').let { p ->
                        p.contains("/$DIFF_DIR/") && p.endsWith(PATCH_FILE_NAME)
                    }
                }
                if (hit) {
                    LOG.info("检测到 patch 文件变更，重新加载")
                    reload()
                }
            }
        })
    }

    private fun notifyChanged() {
        ApplicationManager.getApplication().invokeLater(
            { project.messageBus.syncPublisher(TOPIC).stateChanged() },
            ModalityState.any(),
            project.disposed,
        )
    }
}
