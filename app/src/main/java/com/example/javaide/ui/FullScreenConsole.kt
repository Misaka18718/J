package com.example.javaide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.javaide.IDEViewModel

/**
 * 全屏控制台页面：从屏幕右侧滑入（动画由调用方 AnimatedVisibility 控制）。
 *  - 顶部标题栏：← 返回按钮 + “控制台”标题 + 停止/清空；
 *  - 中部：可滚动的运行输出（始终填充剩余空间）；
 *  - 底部：标准输入区，常驻可见，软键盘弹出时用 imePadding 上推、不遮挡。
 * 程序等待 System.in 时输入框才出现。
 */
@Composable
fun FullScreenConsole(
    vm: IDEViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val text by vm.console.collectAsState()
    val running by vm.isRunning
    val active = vm.programConsole.value
    val scroll = rememberScrollState()

    // 输出区域夜间模式统一浅灰 (#E0E0E0)，日间为深灰；代码高亮不受影响。
    val outColor = if (vm.nightMode.value) Color(0xFFE0E0E0) else Color(0xFF1B1B1B)

    LaunchedEffect(text) {
        scroll.scrollTo(scroll.maxValue)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "控制台",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            if (active != null || running) {
                TextButton(onClick = { vm.stop() }) { Text("停止") }
            }
            TextButton(onClick = { vm.clearConsole() }) { Text("清空") }
        }
        HorizontalDivider()

        // 中部：可滚动输出
        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll)
                .padding(12.dp)
        ) {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = outColor
            )
        }

        // 底部：标准输入区（程序等待 System.in 时可用），常驻且不被键盘遮挡
        if (active != null) {
            var input by remember { mutableStateOf("") }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
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
