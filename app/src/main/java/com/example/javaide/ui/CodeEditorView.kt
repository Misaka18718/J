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

                // 初始加载当前文件（不记录到撤销栈，作为基线）
                vm.currentFile.value?.let { f: File ->
                    if (f.exists()) {
                        vm.loadingFile = true
                        text.setUndoEnabled(false)
                        setText(f.readText())
                        text.setUndoEnabled(true)
                        vm.loadingFile = false
                    }
                }

                // 监听文本变化，解析光标前的“词”作为片段查询
                subscribeAlways(ContentChangeEvent::class.java) {
                    vm.canUndo.value = text.canUndo()
                    vm.canRedo.value = text.canRedo()
                    if (!vm.applyingSnippet && !vm.loadingFile) {
                        val idx = SnippetEngine.caretIndex(this)
                        val token = SnippetEngine.currentToken(text.toString(), idx)
                        vm.setSnippetQuery(token)
                    }
                }

                editorRef.value = this
            }
        }
    )

    // 切换文件时加载新内容（不记录到撤销栈，作为基线）；文件不存在则清空编辑器
    val file = vm.currentFile.value
    LaunchedEffect(file?.absolutePath) {
        val editor = editorRef.value ?: return@LaunchedEffect
        vm.loadingFile = true
        editor.text.setUndoEnabled(false)
        if (file != null && file.exists()) editor.setText(file.readText())
        else editor.setText("")
        editor.text.setUndoEnabled(true)
        vm.loadingFile = false
        vm.setSnippetQuery("")
    }
}
