package com.example.javaide.ui

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.FrameLayout
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

                // 初始加载当前文件
                vm.currentFile.value?.let { f ->
                    if (f.exists()) setText(f.readText())
                }

                // 监听文本变化，解析光标前的“词”作为片段查询
                subscribeAlways<ContentChangeEvent> {
                    if (vm.applyingSnippet) return@subscribeAlways
                    val idx = SnippetEngine.caretIndex(this)
                    val token = SnippetEngine.currentToken(text.toString(), idx)
                    vm.setSnippetQuery(token)
                }

                editorRef.value = this
            }
        }
    )

    // 切换文件时加载新内容
    val file = vm.currentFile.value
    LaunchedEffect(file?.absolutePath) {
        file?.let { f ->
            if (f.exists()) editorRef.value?.setText(f.readText())
        }
    }
}
