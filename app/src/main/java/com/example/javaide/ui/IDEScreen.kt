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
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material.icons.filled.Settings
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
    var newFileName by remember { mutableStateOf("") }
    var newPkgName by remember { mutableStateOf("") }

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
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                manageStorageLauncher.launch(intent)
            } catch (_: Exception) {
                Toast.makeText(context, "无法打开存储权限设置", Toast.LENGTH_SHORT).show()
            }
        } else {
            legacyStorageLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
            TopAppBar(
                title = { Text("Java IDE") },
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
                        onClick = { editorRef.value?.text?.toString()?.let { vm.runCode(it) } },
                        enabled = !running
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "运行")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
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
                                text = { Text("保存") },
                                onClick = {
                                    menuOpen = false
                                    vm.saveContent(editorRef.value?.text?.toString() ?: "")
                                }
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

            Column(Modifier.fillMaxSize().weight(1f)) {
                // 控制台：展开时占约 1/3，折叠时仅顶部一条
                ConsolePanel(
                    vm,
                    Modifier.fillMaxWidth().then(
                        if (vm.consoleExpanded.value) Modifier.weight(1f)
                        else Modifier.height(52.dp)
                    )
                )
                // 标签页栏：横向滚动，点击切换、× 关闭
                AnimatedVisibility(
                    visible = vm.openTabs.value.isNotEmpty(),
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    TabBar(vm)
                }
                // 源代码：占约 2/3
                Box(Modifier.fillMaxWidth().weight(2f)) {
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
            onTogglePublic = { on -> if (on) requestPublicDir() }
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
        var jarName by remember { mutableStateOf("app.jar") }
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
                        placeholder = { Text("输出文件名，如 app.jar") },
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
                TextButton(onClick = {
                    vm.packageJar(jarName, mainClass)
                    showJar = false
                }) { Text("打包") }
            },
            dismissButton = {
                TextButton(onClick = { showJar = false }) { Text("取消") }
            }
        )
    }
}

/** 顶部标签页栏：横向滚动，点击切换激活标签页，× 关闭对应标签页。 */
@Composable
private fun TabBar(vm: IDEViewModel) {
    val openTabs = vm.openTabs.value
    val activeTab = vm.activeTab.value
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
                    .clickable { vm.setActiveTab(i) }
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
                    onClick = { vm.closeTab(i) },
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
            "写入 /storage/emulated/0/JavaIDE_Workspace/（需授权）",
        ) {
            Switch(
                checked = vm.workingDirMode.value == "public",
                onCheckedChange = { onTogglePublic(it) }
            )
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
