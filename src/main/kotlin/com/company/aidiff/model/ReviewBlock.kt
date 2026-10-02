package com.company.aidiff.model

/**
 * 一个「修改块」：hunk 中连续的 删除行（红）+ 新增行（绿）。
 * 用户在单文件内联视图中对每块做 Accept / Reject。
 *
 * 位置元数据（在 patch 解析时确定，用于外科手术式局部回滚）：
 *  - [beforeStartLine]：删除行在基线文件中的起始行（0-based）；
 *  - [afterStartLine]：新增行在当前（AI 修改后）文件中的起始行（0-based）。
 */
enum class BlockState(val displayName: String) {
    UNDECIDED("待确认"),
    ACCEPTED("已接受"),
    REJECTED("已拒绝"),
}

data class ReviewBlock(
    /** 所属文件路径 */
    val changePath: String,
    /** 在所属文件全部块中的序号（按 hunk 顺序） */
    val order: Int,
    /** 被删除的行文本（红色部分，拒绝时保留） */
    val removedLines: List<String>,
    /** 新增的行文本（绿色部分，接受时保留） */
    val addedLines: List<String>,
    /** 删除行在基线文件中的起始行（0-based） */
    val beforeStartLine: Int,
    /** 新增行在当前文件中的起始行（0-based） */
    val afterStartLine: Int,
    /** 当前状态，UI 与业务共用 */
    var state: BlockState = BlockState.UNDECIDED,
) {
    val isUndecided: Boolean get() = state == BlockState.UNDECIDED
}
