package com.company.aidiff.ui

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

/**
 * 块 Inlay 渲染器：在真实文件行上方以「红色背景」绘制被 AI 删除的行。
 * 删除行不写入 Document（不污染工作区，不影响编译/运行），只作为视觉层存在。
 */
class DeletedLinesInlayRenderer(private val deletedLines: List<String>) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        // 宽度覆盖编辑器可视区域；实际背景按 targetRegion 宽度填满
        return inlay.editor.contentComponent.width + 4
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics2D,
        targetRegion: Rectangle2D,
        textAttributes: TextAttributes,
    ) {
        val editor = inlay.editor
        val lineHeight = editor.lineHeight
        val totalHeight = deletedLines.size * lineHeight

        val x = targetRegion.x.toInt()
        val y = targetRegion.y.toInt()
        val width = targetRegion.width.toInt()

        // 红色底
        g.color = BACKGROUND
        g.fillRect(x, y, width, totalHeight)

        // 删除线文本：必须使用编辑器配色方案中的正文字体（字体/字号/缩放与真实代码一致），
        // 不能用 editor.contentComponent.font（Swing 容器字体，字号不同）
        val codeFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        g.font = codeFont
        g.color = TEXT_COLOR
        // 用该字体自身的度量做垂直对齐，避免 ascent 偏差导致文字上下错位
        val ascent = g.getFontMetrics(codeFont).ascent

        // 水平对齐：直接取编辑器真实代码行第 0 列的绘制 X 坐标
        // （包含编辑器左侧内边距，且随水平滚动同步变化），红色文本与绿色代码逐字符对齐。
        // 空文档没有可参考行，退回区域起点。
        val codeStartX = if (editor.document.lineCount > 0)
            editor.visualPositionToXY(VisualPosition(0, 0)).x
        else x

        deletedLines.forEachIndexed { i, text ->
            g.drawString(text, codeStartX, y + ascent + i * lineHeight)
        }

        // 底部分隔细线
        g.color = BORDER_COLOR
        g.drawLine(x, y + totalHeight, x + width, y + totalHeight)
    }

    companion object {
        private val BACKGROUND = JBColor(Color(0xFBEAEA), Color(0x462828))
        private val TEXT_COLOR = JBColor(Color(0xC62828), Color(0xE98B8B))
        private val BORDER_COLOR = JBColor(Color(0xECC7C7), Color(0x5C3434))
    }
}
