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

    /**
     * 取包含 [index] 的行的“缩进”：即该行行首到首个非空白字符之间的空白串。
     * 用于片段插入时让模板整体相对当前行的缩进对齐（修复 psvm 等片段闭合括号顶格的问题）。
     */
    fun lineIndent(text: String, index: Int): String {
        val lineStart = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0)) + 1
        val sb = StringBuilder()
        var i = lineStart
        while (i < text.length && i < index && (text[i] == ' ' || text[i] == '\t')) {
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    /**
     * 给片段模板的每一行前置 [indent]，保持模板内部的相对缩进不变，使整体相对当前行缩进对齐。
     *
     * 关键：**[首行不加] [indent] 前缀**——片段是替换掉光标前的触发词后、紧接着光标所在行已有的
     * 缩进之后插入的，首行本就处于「光标行缩进」之下；若再给首行加 [indent] 会变成「已有缩进 +
     * indent」的双倍缩进（即 psvm 首行多一个 tab 的根因）。因此仅对第 2 行起的前置 [indent]，
     * 首行保持原样（其缩进由光标所在行决定），这样既让闭合 `}` 与首行对齐（v3.12 需求），
     * 又不会让首行多缩进（v3.14 需求）。
     */
    fun indentBody(body: String, indent: String): String {
        val trimmed = body.removeSuffix("\n")
        val lines = trimmed.split("\n")
        return buildString {
            lines.forEachIndexed { i, line ->
                if (i > 0) append(indent)   // 仅后续行加缩进前缀
                append(line)
                if (i < lines.lastIndex) append('\n')
            }
        }
    }
}
