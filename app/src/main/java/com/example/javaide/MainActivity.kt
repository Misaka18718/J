package com.example.javaide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import com.example.javaide.ui.IDEScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm: IDEViewModel = ViewModelProvider(this)[IDEViewModel::class.java]
        setContent {
            val night by vm.nightMode
            MaterialTheme(
                // 夜间模式使用自定义深色配色：背景近黑、所有非代码文字统一浅灰
                // (#E0E0E0 / #F5F5F5)，保证菜单/按钮/对话框/文件树/控制台输出清晰可读。
                // 代码编辑器的语法高亮由 Sora 的 SchemeDarcula 单独控制，不受此影响。
                colorScheme = if (night) darkColorScheme(
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    surfaceVariant = Color(0xFF2A2A2A),
                    onBackground = Color(0xFFE0E0E0),
                    onSurface = Color(0xFFE0E0E0),
                    onSurfaceVariant = Color(0xFFF5F5F5),
                    primary = Color(0xFF90CAF9),
                    onPrimary = Color(0xFF000000),
                    primaryContainer = Color(0xFF1B3A5C),
                    onPrimaryContainer = Color(0xFFE0E0E0),
                    secondary = Color(0xFFB0BEC5),
                    onSecondary = Color(0xFF000000),
                    outline = Color(0xFF5A5A5A),
                    error = Color(0xFFFF8A80),
                ) else lightColorScheme()
            ) {
                IDEScreen(vm)
            }
        }
    }
}
