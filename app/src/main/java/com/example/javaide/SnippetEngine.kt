package com.example.javaide

import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 片段引擎：负责从编辑器光标处解析出当前正在输入的“词”，
 * 并把选中的片段替换掉这个词、把光标放到 `$CARET$` 标记处。
 *
 * 这里刻意只用 Sora 公开稳定的 API：
 *  - [CodeEditor.text]            读取全文
 *  - [CodeEditor.cursor]          读取光标行列
 *  - [CodeEditor.setText]         整体写入
 *  - [CodeEditor.setSelection]    移动光标
 * 避免依赖易变的内部方法，保证在不同 Sora 版本下都能编译运行。
 */
object SnippetEngine {

    private const val CARET = "\$CARET\$"

    /** 匹配“词”的正则：字母/下划线开头，后接字母数字下划线，且位于光标之前。 */
    private val TOKEN_RE = Regex("[A-Za-z_][A-Za-z0-9_]*$")

    /** 根据全文与光标字符下标，取出光标前的“词”。 */
    fun currentToken(text: String, caretIndex: Int): String {
        val upto = text.substring(0, caretIndex.coerceAtMost(text.length))
        return TOKEN_RE.find(upto)?.value ?: ""
    }

    /** 把 [body] 中的 `$CARET$` 替换为空，并计算光标最终应停留的字符下标。 */
    fun apply(body: String, text: String, start: Int, end: Int): Pair<String, Int> {
        val markerIndex = body.indexOf(CARET)
        val cleanBody = body.replace(CARET, "")
        val newText = text.substring(0, start) + cleanBody + text.substring(end)
        val caretIndex = if (markerIndex >= 0) start + markerIndex else start + cleanBody.length
        return newText to caretIndex
    }

    /** 字符下标 -> (行, 列)。 */
    fun indexToLineCol(text: String, index: Int): Pair<Int, Int> {
        var line = 0
        var col = 0
        val max = index.coerceAtMost(text.length)
        for (i in 0 until max) {
            if (text[i] == '\n') {
                line++
                col = 0
            } else {
                col++
            }
        }
        return line to col
    }

    /** (行, 列) -> 字符下标。 */
    fun lineColToIndex(text: String, line: Int, column: Int): Int {
        var idx = 0
        var l = 0
        while (l < line) {
            val nl = text.indexOf('\n', idx)
            if (nl < 0) return text.length
            idx = nl + 1
            l++
        }
        return (idx + column).coerceAtMost(text.length)
    }

    /** 从编辑器光标读取当前字符下标。 */
    fun caretIndex(editor: CodeEditor): Int {
        val text = editor.text.toString()
        return lineColToIndex(text, editor.cursor.leftLine, editor.cursor.leftColumn)
    }
}
