package com.example.javaide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.javaide.IDEViewModel

/**
 * 控制台面板。
 * - 展开时占屏幕上约 1/3，显示运行输出与标准输入区；
 * - 折叠时仅保留顶部一条工具条（约 52dp），可手动展开。
 */
@Composable
fun ConsolePanel(vm: IDEViewModel, modifier: Modifier = Modifier) {
    val text by vm.console.collectAsState()
    val expanded by vm.consoleExpanded
    val running by vm.isRunning
    val active = vm.programConsole.value
    val scroll = rememberScrollState()

    LaunchedEffect(text) {
        scroll.scrollTo(scroll.maxValue)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 顶部工具条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "控制台",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (active != null || running) {
                TextButton(onClick = { vm.stop() }) { Text("停止") }
            }
            TextButton(onClick = { vm.clearConsole() }) { Text("清空") }
            IconButton(onClick = { vm.toggleConsole() }) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "折叠/展开控制台"
                )
            }
        }

        if (expanded) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scroll)
                        .padding(8.dp)
                ) {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }

                // 标准输入区（程序等待 System.in 时可用）
                if (active != null) {
                    var input by remember { mutableStateOf("") }
                    Row(
                        Modifier.fillMaxWidth().padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("输入（供 Scanner / System.in 读取）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                vm.sendInput(input)
                                input = ""
                            })
                        )
                        TextButton(onClick = {
                            vm.sendInput(input)
                            input = ""
                        }) { Text("发送") }
                    }
                }
            }
        }
    }
}
