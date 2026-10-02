package com.company.aidiff.services

import com.company.aidiff.model.ChangeType
import com.company.aidiff.model.FileChange
import com.company.aidiff.model.ReviewBlock
import com.intellij.openapi.diff.impl.patch.PatchHunk
import com.intellij.openapi.diff.impl.patch.PatchLine
import com.intellij.openapi.diff.impl.patch.PatchReader
import com.intellij.openapi.diff.impl.patch.TextFilePatch

/**
 * Patch 解析与位置计算。
 *
 * 新模型（支持边审边改）：
 *  - 每个块记录在基线文件 / 当前文件中的起始行；
 *  - 叠加位置通过 RangeMarker 锚定，块外编辑不影响审查；
 *  - Reject 是外科手术式局部替换，不再全文重写；
 *  - 仅当文件被整体删除（无 Document）时才用 [renderBaselineText] 全文恢复。
 */
object PatchParser {

    enum class LineMark { NORMAL, REMOVED, ADDED }

    /** 新增行在真实 Document 中的初始位置 + 所属块；块 Inlay 的初始插入行 */
    data class RealLineInfo(val lineIndex: Int, val mark: LineMark, val block: ReviewBlock)

    data class RealMapping(
        val lines: List<RealLineInfo>,
        val insertLineForBlock: Map<ReviewBlock, Int>,
    ) {
        fun blockAtLine(line: Int): ReviewBlock? = lines.firstOrNull { it.lineIndex == line }?.block
    }

    /**
     * 解析标准 unified git diff 文本。
     * @throws com.intellij.openapi.diff.impl.patch.PatchSyntaxException patch 格式损坏时抛出
     */
    fun parse(patchText: String): List<FileChange> {
        val patches: List<TextFilePatch> = PatchReader(patchText).readTextPatches()
        return patches.map { tp ->
            var added = 0
            var removed = 0
            var beforeNoFinalNewline = false
            var afterNoFinalNewline = false
            for (hunk in tp.hunks) {
                for (line in hunk.lines) {
                    when (line.type) {
                        PatchLine.Type.ADD -> {
                            added++
                            if (line.isSuppressNewLine) afterNoFinalNewline = true
                        }
                        PatchLine.Type.REMOVE -> {
                            removed++
                            if (line.isSuppressNewLine) beforeNoFinalNewline = true
                        }
                        else -> Unit
                    }
                }
            }
            val type = when {
                tp.isNewFile -> ChangeType.ADDED
                tp.isDeletedFile -> ChangeType.DELETED
                else -> ChangeType.MODIFIED
            }
            val beforeTotal = tp.hunks.maxOfOrNull { it.endLineBefore } ?: 0
            val afterTotal = tp.hunks.maxOfOrNull { it.endLineAfter } ?: 0
            val path = tp.afterName ?: tp.beforeName ?: "unknown"

            FileChange(
                path = path,
                changeType = type,
                addedLines = added,
                deletedLines = removed,
                hunks = tp.hunks,
                blocks = parseBlocks(path, tp.hunks),
                beforeTotalLines = beforeTotal,
                afterTotalLines = afterTotal,
                beforeEndsWithNewline = !beforeNoFinalNewline,
                afterEndsWithNewline = !afterNoFinalNewline,
            )
        }
    }

    /** 新增行/块锚点在真实文件中的初始位置（放置 RangeMarker 与 Inlay 用） */
    fun mapRealDocument(change: FileChange): RealMapping {
        val lines = ArrayList<RealLineInfo>()
        val insertLine = HashMap<ReviewBlock, Int>()
        for (block in change.blocks) {
            insertLine[block] = block.afterStartLine
            block.addedLines.forEachIndexed { j, _ ->
                lines.add(RealLineInfo(block.afterStartLine + j, LineMark.ADDED, block))
            }
        }
        lines.sortBy { it.lineIndex }
        return RealMapping(lines, insertLine)
    }

    /**
     * 渲染基线文件完整文本 —— 仅用于被整体删除的文件（磁盘上不存在，无 Document）。
     */
    fun renderBaselineText(change: FileChange): String {
        val out = ArrayList<String>()
        val blockQueue = ArrayDeque(change.blocks)
        for (hunk in change.hunks) {
            val lines = hunk.lines
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.type == PatchLine.Type.CONTEXT) {
                    out.add(line.text)
                    i++
                    continue
                }
                val block = blockQueue.removeFirst()
                block.removedLines.forEach { out.add(it) }
                i += block.removedLines.size + block.addedLines.size
            }
        }
        val text = out.joinToString("\n")
        return if (change.beforeEndsWithNewline) "$text\n" else text
    }

    /**
     * 解析每个 hunk 中连续的非上下文行为一个修改块，
     * 并记录块在基线/当前文件中的起始行。
     */
    private fun parseBlocks(path: String, hunks: List<PatchHunk>): List<ReviewBlock> {
        val blocks = mutableListOf<ReviewBlock>()
        var order = 0
        for (hunk in hunks) {
            val lines = hunk.lines
            var beforeLine = hunk.startLineBefore
            var afterLine = hunk.startLineAfter
            var i = 0
            while (i < lines.size) {
                if (lines[i].type == PatchLine.Type.CONTEXT) {
                    beforeLine++
                    afterLine++
                    i++
                    continue
                }
                val blockBeforeStart = beforeLine
                val blockAfterStart = afterLine
                val removed = mutableListOf<String>()
                val added = mutableListOf<String>()
                while (i < lines.size && lines[i].type != PatchLine.Type.CONTEXT) {
                    when (lines[i].type) {
                        PatchLine.Type.REMOVE -> {
                            removed.add(lines[i].text)
                            beforeLine++
                        }
                        PatchLine.Type.ADD -> {
                            added.add(lines[i].text)
                            afterLine++
                        }
                        else -> Unit
                    }
                    i++
                }
                blocks.add(
                    ReviewBlock(
                        changePath = path,
                        order = order++,
                        removedLines = removed,
                        addedLines = added,
                        beforeStartLine = blockBeforeStart,
                        afterStartLine = blockAfterStart,
                    ),
                )
            }
        }
        return blocks
    }
}
