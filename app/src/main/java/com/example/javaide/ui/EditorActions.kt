package com.example.javaide.ui

import com.example.javaide.IDEViewModel
import com.example.javaide.Snippet
import com.example.javaide.SnippetEngine
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 把选中的片段替换掉光标前的触发词，并把光标移动到 `$CARET$` 处。
 */
fun insertSnippet(editor: CodeEditor?, snippet: Snippet, vm: IDEViewModel) {
    if (editor == null) return
    vm.applyingSnippet = true
    val text = editor.text.toString()
    val idx = SnippetEngine.caretIndex(editor)
    val token = SnippetEngine.currentToken(text, idx)
    val start = (idx - token.length).coerceAtLeast(0)
    val (newText, caret) = SnippetEngine.apply(snippet.body, text, start, idx)
    editor.setText(newText)
    val (line, col) = SnippetEngine.indexToLineCol(newText, caret)
    editor.setSelection(line, col)
    vm.applyingSnippet = false
    vm.setSnippetQuery("")
}
