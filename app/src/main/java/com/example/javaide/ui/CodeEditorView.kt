package com.example.javaide.ui

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.javaide.IDEViewModel
import com.example.javaide.Snippet
import com.example.javaide.SnippetEngine
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 把 Sora 的 [CodeEditor] 通过 AndroidView 嵌入到 Compose 中，
 * 并接上：Java 语法高亮、等宽字体、片段提示词检测、按文件切换加载。
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
                typefaceText = Typeface.MONOSPACE
                setEditorLanguage(JavaLanguage())

                // 初始加载当前激活标签页内容
                val initContent = vm.activeTabContent()
                if (initContent.isNotEmpty()) setText(initContent)
                else vm.activeTabFile()?.takeIf { it.exists() }?.let { setText(it.readText()) }

                // 监听文本变化，解析光标前的“词”作为片段查询，并回写当前标签页
                subscribeAlways(ContentChangeEvent::class.java) {
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
    }
}
