package com.example.javaide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
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
import androidx.compose.ui.unit.dp
import com.example.javaide.IDEViewModel
import com.example.javaide.Snippets
import io.github.rosemoe.sora.widget.CodeEditor

@Composable
fun IDEScreen(vm: IDEViewModel) {
    val treeOpen by vm.treeOpen
    val running by vm.isRunning
    val snippetQuery by vm.snippetQuery
    val canUndo by vm.canUndo
    val canRedo by vm.canRedo
    val editorRef = remember { mutableStateOf<CodeEditor?>(null) }

    var showNewFile by remember { mutableStateOf(false) }
    var showNewPkg by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var newPkgName by remember { mutableStateOf("") }

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
                        onClick = {
                            editorRef.value?.text?.toString()?.let { vm.runCode(it) }
                        },
                        enabled = !running
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "运行")
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
            if (treeOpen) {
                FileTreePanel(vm, editorRef, Modifier.width(260.dp).fillMaxHeight())
                VerticalDivider()
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
                // 源代码：占约 2/3
                Box(Modifier.fillMaxWidth().weight(2f)) {
                    CodeEditorView(vm, editorRef, Modifier.fillMaxSize())

                    val matches = Snippets.matches(snippetQuery)
                    if (matches.isNotEmpty()) {
                        SnippetPopup(
                            snippets = matches,
                            onPick = { insertSnippet(editorRef.value, it, vm) },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 8.dp, top = 8.dp)
                        )
                    }
                }
            }
        }
    }

    // 新建 Java 文件
    if (showNewFile) {
        AlertDialog(
            onDismissRequest = { showNewFile = false },
            title = { Text("新建 Java 文件") },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    placeholder = { Text("文件名，如 Hello") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFileName.isNotBlank()) {
                        val dir = vm.currentFile.value?.parentFile
                            ?: vm.projectDir.resolve("src")
                        vm.createFile(newFileName, dir)
                        newFileName = ""
                        showNewFile = false
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFile = false }) { Text("取消") }
            }
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
}
