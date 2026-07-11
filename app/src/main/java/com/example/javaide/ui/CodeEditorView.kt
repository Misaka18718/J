package com.example.javaide.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.javaide.IDEViewModel
import com.example.javaide.SnippetEngine
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeEclipse

/**
 * 把 Sora 的 [CodeEditor] 通过 AndroidView 嵌入到 Compose 中，
 * 并接上：Java 语法高亮、等宽字体、片段提示词检测、按文件切换加载、
 * 夜间模式配色、行号开关、字号（含双指缩放）设置。
 */
@Composable
fun CodeEditorView(
    vm: IDEViewModel,
    editorRef: androidx.compose.runtime.MutableState<CodeEditor?>,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CodeEditor(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                typefaceText = android.graphics.Typeface.MONOSPACE
                setEditorLanguage(JavaLanguage())

                // 初始应用各项编辑器设置
                applyEditorSettings(vm, ctx)

                // 双指缩放调整字号（关闭 Sora 自带缩放，自行处理）
                setScalable(false)
                var scaling = false
                val scaleDetector = ScaleGestureDetector(
                    ctx,
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                            scaling = true
                            return true
                        }

                        override fun onScaleEnd(detector: ScaleGestureDetector) {
                            scaling = false
                        }

                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            val dm = resources.displayMetrics
                            val currentSp = textSizePx / dm.scaledDensity
                            val newSp = (currentSp * detector.scaleFactor)
                                .coerceIn(8f, 40f)
                            setTextSize(newSp)
                            vm.setEditorFontSize(newSp)
                            return true
                        }
                    }
                )
                setOnTouchListener { _, event: MotionEvent ->
                    scaleDetector.onTouchEvent(event)
                    // 双指缩放进行中时消费事件，避免 Sora 同时滚动
                    scaling
                }

                // 初始加载当前激活标签页内容
                val initContent = vm.activeTabContent()
                if (initContent.isNotEmpty()) setText(initContent)
                else vm.activeTabFile()?.takeIf { it.exists() }?.let { setText(it.readText()) }

                // 监听文本变化，解析光标前的“词”作为片段查询，并回写当前标签页
                subscribeAlways(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) {
                    vm.canUndo.value = this.text.canUndo()
                    vm.canRedo.value = this.text.canRedo()
                    if (vm.loadingFile) return@subscribeAlways
                    if (!vm.applyingSnippet) {
                        val idx = SnippetEngine.caretIndex(this)
                        vm.setSnippetQuery(SnippetEngine.currentToken(text.toString(), idx))
                    }
                    vm.updateActiveContent(text.toString())
                }

                editorRef.value = this
            }
        }
    )

    // 跟随设置变化实时应用：字号 / 行号 / 配色（夜间模式）
    LaunchedEffect(vm.editorFontSize.value) {
        editorRef.value?.setTextSize(vm.editorFontSize.value)
    }
    LaunchedEffect(vm.showLineNumbers.value) {
        editorRef.value?.setLineNumberEnabled(vm.showLineNumbers.value)
    }
    LaunchedEffect(vm.nightMode.value) {
        editorRef.value?.applyVisualEnhancements(vm)
    }

    // 切换标签页时加载对应内容（保留各标签内存文本，避免相互覆盖）
    val activePath = vm.openTabs.value.getOrNull(vm.activeTab.value)?.absolutePath
    LaunchedEffect(activePath) {
        val ed = editorRef.value ?: return@LaunchedEffect
        val content = vm.activeTabContent()
        vm.loadingFile = true
        ed.setUndoEnabled(false)
        ed.setText(content)
        ed.setUndoEnabled(true)
        vm.loadingFile = false
        vm.setSnippetQuery("")
        // 新建文件后把光标定位到类体空白行（缩进一级）
        val idx = vm.pendingCursorIndex.value
        if (idx >= 0) {
            val (line, col) = SnippetEngine.indexToLineCol(content, idx)
            ed.setSelection(line, col)
            vm.pendingCursorIndex.value = -1
        }
    }
}

/** 一次性把当前设置套用到编辑器实例（创建时调用）。 */
private fun CodeEditor.applyEditorSettings(vm: IDEViewModel, ctx: Context) {
    setTextSize(vm.editorFontSize.value)
    setLineNumberEnabled(vm.showLineNumbers.value)
    applyVisualEnhancements(vm)
}

/**
 * 应用编辑器视觉增强（随夜间模式切换配色）：
 *  - 括号匹配高亮：光标挨着括号时高亮配对括号（背景/边框）；
 *  - 缩进参考线：从代码块起始行延伸到结束行的浅灰竖线；
 *  - 连续空格/制表符可视化：仅绘制行首缩进（通常为连续 ≥2 空格或 Tab），
 *    不绘制单词间的单个空格，避免视觉噪声。
 */
private fun CodeEditor.applyVisualEnhancements(vm: IDEViewModel) {
    val night = vm.nightMode.value
    colorScheme = if (night) SchemeDarcula() else SchemeEclipse()

    // 配色覆盖（括号内为 ARGB）
    colorScheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, 0x334A90D9.toInt())
    colorScheme.setColor(EditorColorScheme.MATCHED_TEXT_BORDER, 0xFF4A90D9.toInt())
    colorScheme.setColor(
        EditorColorScheme.BLOCK_LINE,
        if (night) 0xFF555555.toInt() else 0xFFD0D0D0.toInt()
    )
    colorScheme.setColor(
        EditorColorScheme.BLOCK_LINE_CURRENT,
        if (night) 0xFF666666.toInt() else 0xFFB0B0B0.toInt()
    )
    colorScheme.setColor(
        EditorColorScheme.NON_PRINTABLE_CHAR,
        if (night) 0xFF666666.toInt() else 0xFF888888.toInt()
    )

    // 括号匹配高亮
    setHighlightBracketPair(true)
    // 缩进参考线（浅灰、半透明观感）
    setBlockLineEnabled(true)
    setBlockLineWidth(1f)
    // 空格可视化：行首缩进（连续 ≥2 空格或 Tab），不含单词间单个空格
    setNonPrintablePaintingFlags(
        CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or CodeEditor.FLAG_DRAW_TAB_SAME_AS_SPACE
    )
}
