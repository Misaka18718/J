package com.example.javaide.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * [CodeEditor] 子类：在原生绘制之上叠加“连续空格可视化”。
 *
 * 规则（对应需求二）：
 *  - 仅当**连续空格数量 ≥ 2** 时，每个空格绘制一个**圆点**；
 *  - 单个空格不绘制；
 *  - 颜色：日间 #888888 / 夜间 #666666（由 [spaceDotColor] 控制）。
 *
 * 说明：原生 [CodeEditor.setNonPrintablePaintingFlags] 只能按“区域”（行首 / 行内 /
 * 行尾）批量绘制，无法区分“单个空格”与“连续 ≥2 空格”，且绘制的是小圆点而非 `·`；
 * 为保证各设备（含等宽字体缺字形的情况）都能稳定可见，此处改用自定义叠加绘制，
 * 直接画**圆点**（与 `·` 等价的空白占位提示），精确满足“≥2 连续空格才显示”的规则。
 */
class SpaceDotEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : CodeEditor(context, attrs) {

    /** 连续空格点的颜色，随夜间模式切换。 */
    var spaceDotColor: Int = 0xFF888888.toInt()

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawConsecutiveSpaceDots(canvas)
    }

    private fun drawConsecutiveSpaceDots(canvas: Canvas) {
        val layout = layout ?: return
        val text = text ?: return
        val lineCount = text.lineCount
        if (lineCount == 0) return

        val firstLine = getFirstVisibleLine().coerceAtLeast(0)
        val lastLine = getLastVisibleLine().coerceAtMost(lineCount - 1)
        if (firstLine > lastLine) return

        // 复用编辑器自身字体 / 字号，保证圆点与正文对齐
        dotPaint.typeface = getTypefaceText()
        dotPaint.textSize = getTextSizePx()
        dotPaint.color = spaceDotColor

        // 空格宽度用于把圆点放在每个空格的中心；圆点半径与字号成比例（保证最小可见）
        val halfSpace = dotPaint.measureText(" ") / 2f
        val dotR = (getTextSizePx() * 0.08f).coerceAtLeast(2f)
        val offsetY = getOffsetY().toFloat()
        val rowH = getRowHeightOfText()

        for (ln in firstLine..lastLine) {
            val line = text.getLine(ln)
            val len = line.length
            if (len == 0) continue

            // 文本行垂直中心（减去纵向滚动量，与渲染器坐标系一致）
            val cy = getRowTopOfText(ln) - offsetY + rowH / 2f

            // 扫描连续空格，长度 ≥ 2 时每个空格绘制一个圆点（单个空格不绘制）
            var runStart = -1
            for (c in 0..len) {
                val isSpace = c < len && line[c] == ' '
                if (isSpace) {
                    if (runStart < 0) runStart = c
                } else {
                    if (runStart >= 0) {
                        val runLen = c - runStart
                        if (runLen >= 2) {
                            for (sc in runStart until c) {
                                val x = getOffset(ln, sc) + halfSpace
                                canvas.drawCircle(x, cy, dotR, dotPaint)
                            }
                        }
                        runStart = -1
                    }
                }
            }
        }
    }
}
