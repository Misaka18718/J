package com.example.javaide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.javaide.IDEViewModel
import com.example.javaide.SnippetEngine
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 底部快捷符号输入栏（Java 编辑器专用）。
 *
 * 布局（从上到下，对应需求一/二）：
 *  - 第 1 行（常驻，始终可见）：`→ / + - * = <`（均匀分布）；
 *  - 上滑展开后，在其**下方**依次出现：
 *      - 第 2 行：“快捷栏”标题（居中）；
 *      - 第 3 行：`> " ' ; | \ _`（均匀分布）；
 *      - 第 4 行：`() [] {}`（**向左对齐**，紧贴左边缘，与第 1 行左边缘对齐）。
 *  即展开顺序严格为 **1 → 2 → 3 → 4**。
 *
 * 动画（需求一）：上滑展开、下滑收回，支持**拖拽式展开**——展开高度随手指连续变化，
 * 可停在半展开状态，也可合上（松手后保持当前进度，不进行强制吸附）。
 *
 * 始终位于键盘之上（imePadding）。`→` 插入 Tab，其余单字符直接插入；
 * 第 4 行成对符号插入一对并把光标移到中间。
 */
@Composable
fun SymbolBar(
    vm: IDEViewModel,
    editorRef: androidx.compose.runtime.MutableState<CodeEditor?>,
    modifier: Modifier = Modifier
) {
    // 展开进度：0 = 完全折叠，1 = 完全展开，中间值 = 半展开
    var progress by remember { mutableStateOf(0f) }
    // 展开内容的自然高度（px），由内部测量得到
    val fullHeightPx = remember { mutableStateOf(0) }
    // 屏幕密度，用于把 px 高度换算成 dp（Modifier.height 接收 dp）
    val density = LocalDensity.current.density

    // 第 1 行（常驻符号）
    val row1 = listOf(
        "→" to { ed: CodeEditor? -> insertSymbol(vm, ed, "\t") },
        "/" to { ed: CodeEditor? -> insertSymbol(vm, ed, "/") },
        "+" to { ed: CodeEditor? -> insertSymbol(vm, ed, "+") },
        "-" to { ed: CodeEditor? -> insertSymbol(vm, ed, "-") },
        "*" to { ed: CodeEditor? -> insertSymbol(vm, ed, "*") },
        "=" to { ed: CodeEditor? -> insertSymbol(vm, ed, "=") },
        "<" to { ed: CodeEditor? -> insertSymbol(vm, ed, "<") },
    )
    // 第 3 行（展开态第二排符号）
    val row3 = listOf(
        ">" to { ed: CodeEditor? -> insertSymbol(vm, ed, ">") },
        "\"" to { ed: CodeEditor? -> insertSymbol(vm, ed, "\"") },
        "'" to { ed: CodeEditor? -> insertSymbol(vm, ed, "'") },
        ";" to { ed: CodeEditor? -> insertSymbol(vm, ed, ";") },
        "|" to { ed: CodeEditor? -> insertSymbol(vm, ed, "|") },
        "\\" to { ed: CodeEditor? -> insertSymbol(vm, ed, "\\") },
        "_" to { ed: CodeEditor? -> insertSymbol(vm, ed, "_") },
    )
    // 第 4 行（成对符号，光标移到中间）—— 左对齐排列
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
            // 拖拽式展开：高度随手指连续变化，松手后保持当前进度（可半展开 / 合上）
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, amount ->
                        val h = fullHeightPx.value.toFloat().coerceAtLeast(1f)
                        // 上滑（amount<0）增大进度，下滑减小
                        progress = (progress - amount / h).coerceIn(0f, 1f)
                    }
                )
            }
    ) {
        // ===== 第 1 行：常驻 7 个符号（始终可见） =====
        SymbolRow(editorRef = editorRef, symbols = row1, weight = true)

        // ===== 展开内容（第 2/3/4 行）=====
        // 用固定高度的裁剪框承载，高度 = 进度 × 自然高度，实现“从上到下依次显示”
        // 且可停在半展开状态。框内顺序固定为：标题 → 第3行 → 第4行。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((fullHeightPx.value * progress / density).dp)
                .clipToBounds()
        ) {
            // 内部测量自然高度：wrapContentHeight(unbounded) 使其始终按完整内容测量，
            // 不受外层裁剪框高度约束，从而拿到真实 fullHeightPx。
            Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(unbounded = true)
                    .onSizeChanged { fullHeightPx.value = it.height }
                    .padding(vertical = 2.dp)
            ) {
                // 第 2 行：标题（居中）
                Text(
                    "快捷栏",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 第 3 行（均匀分布）
                SymbolRow(editorRef = editorRef, symbols = row3, weight = true)
                // 第 4 行（左对齐，紧贴左边缘，与第 1 行左边缘对齐）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start)
                ) {
                    row4.forEach { (label, action) ->
                        SymbolKey(label, weight = false) { action(editorRef.value) }
                    }
                }
            }
        }
    }
}

/** 横向排列的一行符号键。weight=true 时均分宽度（第 1、3 行）；false 时按内容宽度（第 4 行左对齐）。 */
@Composable
private fun SymbolRow(
    editorRef: androidx.compose.runtime.MutableState<CodeEditor?>,
    symbols: List<Pair<String, (CodeEditor?) -> Unit>>,
    weight: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        symbols.forEach { (label, action) ->
            SymbolKey(label, weight) { action(editorRef.value) }
        }
    }
}

/** 单个符号键：点击即触发插入。声明为 RowScope 扩展，使 weight 可用。 */
@Composable
private fun RowScope.SymbolKey(label: String, weight: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .then(if (weight) Modifier.weight(1f) else Modifier.wrapContentWidth())
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = if (weight) 0.dp else 2.dp),
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
