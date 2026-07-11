package com.example.javaide.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.javaide.IDEViewModel
import com.example.javaide.SnippetEngine
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 底部快捷符号输入栏（Java 编辑器专用）。
 *  - 默认折叠：仅一行 7 个符号 `→ / + - * = <`，无标题；
 *  - 上滑展开：新增“快捷栏”标题行 + 第三行 `> " ' ; | \ _` + 第四行 `() [] {}`；
 *  - 下滑收起（动画反向）。
 * 始终位于键盘之上（imePadding）。`→` 插入 Tab，其余单字符直接插入；
 * 第四行成对符号插入一对并把光标移到中间。
 */
@Composable
fun SymbolBar(
    vm: IDEViewModel,
    editorRef: androidx.compose.runtime.MutableState<CodeEditor?>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val dragTotal = remember { mutableStateOf(0f) }

    // 折叠态常驻的那一行（7 个符号）
    val baseSymbols = listOf(
        "→" to { ed: CodeEditor? -> insertSymbol(vm, ed, "\t") },
        "/" to { ed: CodeEditor? -> insertSymbol(vm, ed, "/") },
        "+" to { ed: CodeEditor? -> insertSymbol(vm, ed, "+") },
        "-" to { ed: CodeEditor? -> insertSymbol(vm, ed, "-") },
        "*" to { ed: CodeEditor? -> insertSymbol(vm, ed, "*") },
        "=" to { ed: CodeEditor? -> insertSymbol(vm, ed, "=") },
        "<" to { ed: CodeEditor? -> insertSymbol(vm, ed, "<") },
    )
    // 展开态第三行
    val row3 = listOf(
        ">" to { ed: CodeEditor? -> insertSymbol(vm, ed, ">") },
        "\"" to { ed: CodeEditor? -> insertSymbol(vm, ed, "\"") },
        "'" to { ed: CodeEditor? -> insertSymbol(vm, ed, "'") },
        ";" to { ed: CodeEditor? -> insertSymbol(vm, ed, ";") },
        "|" to { ed: CodeEditor? -> insertSymbol(vm, ed, "|") },
        "\\" to { ed: CodeEditor? -> insertSymbol(vm, ed, "\\") },
        "_" to { ed: CodeEditor? -> insertSymbol(vm, ed, "_") },
    )
    // 展开态第四行（成对符号，光标移到中间）
    val row4 = listOf(
        "()" to { ed: CodeEditor? -> insertSymbol(vm, ed, "()", pair = true) },
        "[]" to { ed: CodeEditor? -> insertSymbol(vm, ed, "[]", pair = true) },
        "{}" to { ed: CodeEditor? -> insertSymbol(vm, ed, "{}", pair = true) },
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .imePadding()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragTotal.value = 0f },
                    onVerticalDrag = { _, amount -> dragTotal.value += amount },
                    onDragEnd = {
                        if (dragTotal.value < -40f) expanded = true
                        else if (dragTotal.value > 40f) expanded = false
                    }
                )
            }
    ) {
        // 展开时新增的三行（标题 + 第三行 + 第四行），置于常驻行上方
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "快捷栏",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SymbolRow(editorRef = editorRef, symbols = row3)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start)
                ) {
                    row4.forEach { (label, action) ->
                        SymbolKey(label) { action(editorRef.value) }
                    }
                }
            }
        }

        // 常驻行：7 个符号
        SymbolRow(editorRef = editorRef, symbols = baseSymbols)
    }
}

/** 横向排列的一行符号键，均分宽度。 */
@Composable
private fun SymbolRow(
    editorRef: androidx.compose.runtime.MutableState<CodeEditor?>,
    symbols: List<Pair<String, (CodeEditor?) -> Unit>>
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        symbols.forEach { (label, action) ->
            SymbolKey(label) { action(editorRef.value) }
        }
    }
}

/** 单个符号键：点击即触发插入。声明为 RowScope 扩展，使 weight 可用。 */
@Composable
private fun RowScope.SymbolKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 在编辑器光标处插入文本。pair=true 时把光标置于成对符号中间。
 * 复用已验证的 setText + indexToLineCol 方案，并临时关闭片段提示，避免误触发。
 */
private fun insertSymbol(
    vm: IDEViewModel,
    editor: CodeEditor?,
    text: String,
    pair: Boolean = false
) {
    if (editor == null) return
    vm.applyingSnippet = true
    val src = editor.text.toString()
    val idx = SnippetEngine.caretIndex(editor)
    val newText = src.substring(0, idx) + text + src.substring(idx)
    val caret = if (pair) idx + text.length / 2 else idx + text.length
    editor.setText(newText)
    val (line, col) = SnippetEngine.indexToLineCol(newText, caret)
    editor.setSelection(line, col)
    vm.applyingSnippet = false
    vm.setSnippetQuery("")
}
