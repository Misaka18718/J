package com.example.javaide.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.javaide.IDEViewModel
import com.example.javaide.Snippets
import com.example.javaide.ui.FullScreenConsole
import com.example.javaide.ui.SymbolBar
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

@Composable
fun IDEScreen(vm: IDEViewModel) {
    val treeOpen by vm.treeOpen
    val running by vm.isRunning
    val snippetQuery by vm.snippetQuery
    val canUndo by vm.canUndo
    val canRedo by vm.canRedo
    val editorRef = remember { mutableStateOf<CodeEditor?>(null) }
    val context = LocalContext.current

    var showNewFile by remember { mutableStateOf(false) }
    var showNewPkg by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showJar by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var newPkgName by remember { mutableStateOf("") }

    // 收集 ViewModel 的 Toast 提示并弹出（创建 src/out/保存等操作的用户反馈）
    val toastCtx = context
    LaunchedEffect(Unit) {
        vm.toast.collect { msg -> Toast.makeText(toastCtx, msg, Toast.LENGTH_SHORT).show() }
    }

    // 公共存储权限（MANAGE_EXTERNAL_STORAGE 走设置页 Intent；低版本走运行时权限）
    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            vm.applyWorkingDir("public")
        } else {
            vm.appendConsole(">>> 未授予所有文件访问权限，仍使用私有目录\n")
        }
    }
    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.applyWorkingDir("public")
        else vm.appendConsole(">>> 未获得存储权限，仍使用私有目录\n")
    }
    fun requestPublicDir() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 已授权则直接切换，无需跳设置
            if (Environment.isExternalStorageManager()) {
                vm.applyWorkingDir("public")
                return
            }
            // 1. 首选：直接打开本应用的"所有文件访问"页面（API 30+）
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                manageStorageLauncher.launch(intent)
            } catch (_: Exception) {
                // 2. 降级：打开"所有文件访问"应用列表页
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageStorageLauncher.launch(intent)
                } catch (_: Exception) {
                    // 3. 最终降级：打开应用详情设置页
                    try {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                        manageStorageLauncher.launch(intent)
                    } catch (_: Exception) {
                        vm.appendConsole(">>> 无法自动打开权限设置\n")
                        vm.appendConsole(">>> 请手动前往：系统设置 → 应用 → JavaIDE → 所有文件访问权限\n")
                    }
                }
            }
        } else {
            legacyStorageLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // JAR 文件选择器（v3.0：运行外部 .jar）
    val jarPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val name = uri.lastPathSegment ?: "temp.jar"
                val tmp = File(context.cacheDir, "jar_run_$name")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                vm.runJar(tmp.absolutePath)
            } catch (e: Exception) {
                vm.appendConsole(">>> 无法读取 JAR 文件：${e.message}\n")
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
            TopAppBar(
                // 移除标题文字，给右侧操作图标（撤销/重做/运行/控制台/设置/保存/更多）让出空间
                title = { },
                navigationIcon = {
                    IconButton(onClick = { vm.toggleTree() }) {
                        Icon(Icons.Filled.Menu, contentDescription = "目录")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { editorRef.value?.text?.undo() },
                        enabled = canUndo
                    ) {
                        Icon(Icons.Filled.Undo, contentDescription = "撤销")
                    }
                    IconButton(
                        onClick = { editorRef.value?.text?.redo() },
                        enabled = canRedo
                    ) {
                        Icon(Icons.Filled.Redo, contentDescription = "重做")
                    }
                    IconButton(
                        onClick = {
                            editorRef.value?.text?.toString()?.let {
                                vm.runCode(it)
                                showConsole = true
                            }
                        },
                        enabled = !running
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "运行")
                    }
                    // 独立控制台入口：仅打开全屏控制台，不触发编译/运行
                    IconButton(onClick = { showConsole = true }) {
                        Icon(Icons.Filled.Terminal, contentDescription = "控制台")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                    IconButton(
                        onClick = { vm.saveContent(editorRef.value?.text?.toString() ?: "") }
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "保存")
                    }
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("新建 Java 文件") },
                                onClick = { menuOpen = false; showNewFile = true }
                            )
                            DropdownMenuItem(
                                text = { Text("选择文件夹（新建文件目录）") },
                                onClick = { menuOpen = false; showFolderPicker = true }
                            )
                            DropdownMenuItem(
                                text = { Text("新建包") },
                                onClick = { menuOpen = false; showNewPkg = true }
                            )
                            DropdownMenuItem(
                                text = { Text("创建 src") },
                                onClick = { menuOpen = false; vm.createSrc() }
                            )
                            DropdownMenuItem(
                                text = { Text("创建 out") },
                                onClick = { menuOpen = false; vm.createOut() }
                            )
                            DropdownMenuItem(
                                text = { Text("打包 JAR") },
                                onClick = { menuOpen = false; showJar = true }
                            )
                            DropdownMenuItem(
                                text = { Text("运行 .jar") },
                                onClick = { menuOpen = false; jarPickerLauncher.launch(arrayOf("*/*")) }
                            )
                            DropdownMenuItem(
                                text = { Text("清空控制台") },
                                onClick = { menuOpen = false; vm.clearConsole() }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(visible = treeOpen) {
                Row {
                    FileTreePanel(vm, editorRef, Modifier.width(260.dp).fillMaxHeight())
                    VerticalDivider()
                }
            }

            // 编辑器侧容器：文件树展开时，在其上覆盖一层透明点击区，
            // 点击右侧（编辑区 / 空屏）即收起文件树（需求四，等效于点左上角三道杠）。
            Box(Modifier.fillMaxSize().weight(1f)) {
                Column(Modifier.fillMaxSize()) {
                // 标签页栏：横向滚动，点击切换、× 关闭
                AnimatedVisibility(
                    visible = vm.openTabs.value.isNotEmpty(),
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    TabBar(vm)
                }
                // 源代码：占满剩余空间（控制台已改为全屏独立页面）
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    CodeEditorView(vm, editorRef, Modifier.fillMaxSize())

                    // 用独立 Column 承载片段弹窗，使 AnimatedVisibility 只处于 ColumnScope，
                    // 避免与外层 BoxScope 的扩展冲突
                    Column(Modifier.fillMaxSize()) {
                        val matches = Snippets.matches(snippetQuery)
                        AnimatedVisibility(
                            visible = matches.isNotEmpty(),
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            SnippetPopup(
                                snippets = matches,
                                onPick = { insertSnippet(editorRef.value, it, vm) },
                                modifier = Modifier
                                    .padding(start = 8.dp, top = 8.dp)
                            )
                        }
                    }

                    // 未打开任何文件时禁止编辑，并提示用户打开 / 新建
                    if (vm.openTabs.value.isEmpty()) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "请打开或新建一个 Java 文件",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // 底部快捷符号输入栏：随键盘上推、始终在键盘之上
                SymbolBar(vm, editorRef)
                }
                // 第二种关闭文件树方式：点击右侧露出区域即收起（透明层，不遮挡视觉）
                if (treeOpen) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable { vm.toggleTree() }
                    )
                }
            }
        }
        }

        // 设置界面
    AnimatedVisibility(
        visible = showSettings,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        SettingsScreen(
            vm = vm,
            onBack = { showSettings = false },
            onTogglePublic = { on -> if (on) requestPublicDir() else vm.applyWorkingDir("private") }
        )
    }

    // 全屏控制台：点击 ▶ 时从右侧滑入，← 返回滑出
    AnimatedVisibility(
        visible = showConsole,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it })
    ) {
        FullScreenConsole(
            vm = vm,
            onBack = { showConsole = false },
            modifier = Modifier.fillMaxSize()
        )
    }
    }

    // 新建 Java 文件
    if (showNewFile) {
        val target = vm.targetDirForNewFile()
        AlertDialog(
            onDismissRequest = { showNewFile = false },
            title = { Text("新建 Java 文件") },
            text = {
                Column {
                    Text(
                        "目标目录：${target.absolutePath.removePrefix(vm.projectDir.absolutePath)
                            .ifBlank { "/" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        placeholder = { Text("文件名，如 Hello") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFileName.isNotBlank()) {
                        vm.createFile(newFileName, vm.targetDirForNewFile())
                        newFileName = ""
                        vm.selectedDir.value = null
                        showNewFile = false
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFile = false }) { Text("取消") }
            }
        )
    }

    // 文件夹选择（新建文件目录）
    if (showFolderPicker) {
        FolderPickerDialog(
            root = vm.projectDir,
            current = vm.selectedDir.value,
            onPick = { vm.selectedDir.value = it; showFolderPicker = false },
            onDismiss = { showFolderPicker = false }
        )
    }

    // 新建包路径
    if (showNewPkg) {
        AlertDialog(
            onDismissRequest = { showNewPkg = false },
            title = { Text("新建包") },
            text = {
                OutlinedTextField(
                    value = newPkgName,
                    onValueChange = { newPkgName = it },
                    placeholder = { Text("如 com.example.demo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPkgName.isNotBlank()) {
                        vm.createPackage(newPkgName)
                        newPkgName = ""
                        showNewPkg = false
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showNewPkg = false }) { Text("取消") }
            }
        )
    }

    // 打包 JAR
    if (showJar) {
        var jarName by remember { mutableStateOf("") }
        var mainClass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJar = false },
            title = { Text("打包 JAR") },
            text = {
                Column {
                    Text(
                        "将 src 下全部源码编译为 classes.jar，并输出到 jars/ 目录。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = jarName,
                        onValueChange = { jarName = it },
                        placeholder = { Text("xxx.jar") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = mainClass,
                        onValueChange = { mainClass = it },
                        placeholder = { Text("入口类（可选，如 com.example.demo.Main）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.packageJar(jarName, mainClass)
                        showJar = false
                    },
                    enabled = jarName.isNotBlank()
                ) { Text("打包") }
            },
            dismissButton = {
                TextButton(onClick = { showJar = false }) { Text("取消") }
            }
        )
    }

    // 多 main 主类选择器（v3.0 增强：用户可从多个入口类中真正选择要运行的类）
    val mainChoices = vm.mainClassChoices.value
    if (mainChoices != null) {
        AlertDialog(
            onDismissRequest = { vm.cancelMainClass() },
            title = { Text("选择入口类") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    mainChoices.forEach { cls ->
                        TextButton(
                            onClick = { vm.chooseMainClass(cls) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(cls, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { },
            dismissButton = {
                TextButton(onClick = { vm.cancelMainClass() }) { Text("取消") }
            }
        )
    }
}

/** 顶部标签页栏：横向滚动，点击切换激活标签页，× 关闭对应标签页。 */
@Composable
private fun TabBar(vm: IDEViewModel) {
    val openTabs = vm.openTabs.value
    val activeTab = vm.activeTab.value
    // 待关闭 / 待切换且需确认保存的标签页索引（为 null 表示不弹窗）
    var pendingClose by remember { mutableStateOf<Int?>(null) }
    var pendingSwitch by remember { mutableStateOf<Int?>(null) }
    // 确认退出的第二层对话框（晚于保存提示；无论是否修改都弹）
    var pendingExit by remember { mutableStateOf<Int?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .height(38.dp)
    ) {
        openTabs.forEachIndexed { i, file ->
            val selected = i == activeTab
            Row(
                modifier = Modifier
                    .clickable {
                        // 切换到另一个已脏的标签页时，先确认保存
                        if (i != activeTab && vm.isTabDirty(activeTab)) {
                            pendingSwitch = i
                        } else {
                            vm.setActiveTab(i)
                        }
                    }
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(file.name, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        // 不论是否修改，都先走保存确认（如有修改），再走退出确认
                        if (vm.isTabDirty(i)) pendingClose = i
                        else pendingExit = i
                    },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭标签页",
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }

    // 未保存退出（关闭）的确认对话框：保存 / 不保存 / 取消
    if (pendingClose != null) {
        val idx = pendingClose!!
        val name = openTabs.getOrNull(idx)?.name ?: ""
        AlertDialog(
            onDismissRequest = { pendingClose = null },
            title = { Text("未保存的修改") },
            text = { Text("“$name” 含有未保存的修改，是否保存后再关闭？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveTab(idx)
                    pendingClose = null
                    pendingExit = idx
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingClose = null
                        pendingExit = idx
                    }) { Text("不保存") }
                    TextButton(onClick = { pendingClose = null }) { Text("取消") }
                }
            }
        )
    }

    // 确认退出的对话框：晚于"是否保存"，不论修改与否都弹出
    if (pendingExit != null) {
        val idx = pendingExit!!
        val name = openTabs.getOrNull(idx)?.name ?: ""
        AlertDialog(
            onDismissRequest = { pendingExit = null },
            title = { Text("确认关闭") },
            text = { Text("是否关闭文件“$name”？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeTab(idx, save = false)
                    pendingExit = null
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExit = null }) { Text("取消") }
            }
        )
    }

    // 未保存退出（切换到其它标签）的确认对话框：保存 / 不保存 / 取消
    if (pendingSwitch != null) {
        val target = pendingSwitch!!
        val name = openTabs.getOrNull(activeTab)?.name ?: ""
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("未保存的修改") },
            text = { Text("“$name” 含有未保存的修改，是否保存后再切换？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveTab(activeTab)
                    vm.setActiveTab(target)
                    pendingSwitch = null
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        vm.setActiveTab(target)
                        pendingSwitch = null
                    }) { Text("不保存") }
                    TextButton(onClick = { pendingSwitch = null }) { Text("取消") }
                }
            }
        )
    }
}

/** 设置界面：夜间模式、字号、行号、自动保存、控制台行数、工作目录。 */
@Composable
private fun SettingsScreen(
    vm: IDEViewModel,
    onBack: () -> Unit,
    onTogglePublic: (Boolean) -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scroll)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()

        // 夜间模式
        SettingRow("夜间模式", "深色背景与配色") {
            Switch(checked = vm.nightMode.value, onCheckedChange = { vm.setNightMode(it) })
        }

        // 字号
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "编辑器字号：${vm.editorFontSize.value.toInt()} sp",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = vm.editorFontSize.value,
                onValueChange = { vm.setEditorFontSize(it) },
                valueRange = 8f..32f,
                steps = 24
            )
            Text("也可在编辑器中双指缩放调整", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()

        // 行号
        SettingRow("显示行号", "编辑器左侧显示行号") {
            Switch(checked = vm.showLineNumbers.value, onCheckedChange = { vm.setShowLineNumbers(it) })
        }

        // 自动保存
        SettingRow("自动保存", "停止输入 0.8s 后自动落盘") {
            Switch(checked = vm.autoSave.value, onCheckedChange = { vm.setAutoSave(it) })
        }

        // 控制台最大行数
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "控制台最大行数：${vm.consoleMaxLines.value}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = vm.consoleMaxLines.value.toFloat(),
                onValueChange = { vm.setConsoleMaxLines(it.toInt()) },
                valueRange = 200f..5000f,
                steps = 48
            )
        }
        HorizontalDivider()

        // 工作目录
        SettingRow(
            "使用公共存储",
            "写入 /storage/emulated/0/ 下自定义文件夹（需授权）",
        ) {
            Switch(
                checked = vm.workingDirMode.value == "public",
                onCheckedChange = { onTogglePublic(it) }
            )
        }
        if (vm.workingDirMode.value == "public") {
            var customPath by remember { mutableStateOf(vm.publicStoragePath.value) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customPath,
                    onValueChange = { customPath = it },
                    label = { Text("文件夹名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { vm.setPublicStoragePath(customPath) },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("应用") }
            }
        }
        Text(
            "当前工作目录：${vm.projectDir.absolutePath}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

/** 一行设置项：左侧标题+副标题，右侧控件。 */
@Composable
private fun SettingRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        control()
    }
}

/** 文件夹选择对话框：目录树单选，用于指定新建文件的目标目录。 */
@Composable
private fun FolderPickerDialog(
    root: File,
    current: File?,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val expanded = remember { mutableStateOf(setOf(root.absolutePath)) }
    val picked = remember { mutableStateOf<File?>(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择文件夹") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                DirNode(
                    dir = root,
                    depth = 0,
                    expanded = expanded,
                    picked = picked
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                picked.value?.let { onPick(it) } ?: onDismiss()
            }) { Text("选择") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun DirNode(
    dir: File,
    depth: Int,
    expanded: androidx.compose.runtime.MutableState<Set<String>>,
    picked: androidx.compose.runtime.MutableState<File?>
) {
    val path = dir.absolutePath
    val isExpanded = expanded.value.contains(path)
    val isPicked = picked.value?.absolutePath == path
    val children = remember(dir) {
        (dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList())
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    picked.value = dir
                    expanded.value = if (isExpanded) expanded.value - path
                    else expanded.value + path
                }
                .background(
                    if (isPicked) MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent,
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(start = (depth * 14 + 8).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isExpanded) Icons.Filled.MoreVert else Icons.Filled.Menu,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(dir.name.ifBlank { "/" }, style = MaterialTheme.typography.bodyMedium)
        }
        if (isExpanded) {
            children.forEach { child ->
                DirNode(child, depth + 1, expanded, picked)
            }
        }
    }
}
