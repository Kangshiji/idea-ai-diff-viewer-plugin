package com.company.aidiff.model

import com.intellij.openapi.diff.impl.patch.PatchHunk

/**
 * 单个文件的变更描述（UI 与业务共用的数据模型）
 */
enum class ChangeType(val displayName: String) {
    ADDED("新增"),
    MODIFIED("修改"),
    DELETED("删除"),
}

data class FileChange(
    /** 相对项目根目录的路径（已去掉 git 风格的 a/ b/ 前缀），使用 '/' 分隔 */
    val path: String,
    val changeType: ChangeType,
    val addedLines: Int,
    val deletedLines: Int,
    /** 该文件在 patch 中的全部变更块，用于内联视图渲染与内容重建 */
    val hunks: List<PatchHunk>,
    /** 从 hunks 派生出的修改块列表（按文件内顺序） */
    val blocks: List<ReviewBlock>,
    /** 基线文件总行数 */
    val beforeTotalLines: Int,
    /** 当前（AI 修改后）文件总行数 */
    val afterTotalLines: Int,
    /** 基线文件末尾是否带换行 */
    val beforeEndsWithNewline: Boolean,
    /** 当前文件末尾是否带换行 */
    val afterEndsWithNewline: Boolean,
    /** 是否已被用户整体确认接受 */
    var accepted: Boolean = false,
) {
    fun undecidedBlocks(): List<ReviewBlock> = blocks.filter { it.isUndecided }

    fun allBlocksDecided(): Boolean = blocks.isNotEmpty() && blocks.none { it.isUndecided }
}
