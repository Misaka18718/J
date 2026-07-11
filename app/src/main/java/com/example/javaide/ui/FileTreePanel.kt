package com.example.javaide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.javaide.FileNode
import com.example.javaide.IDEViewModel
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 左侧可折叠目录树。支持：
 *  - 单击目录展开/折叠、单击文件打开；
 *  - 长按任意节点弹出“重命名 / 删除”菜单。
 */
@Composable
fun FileTreePanel(
    vm: IDEViewModel,
    editorRef: androidx.compose.runtime.MutableState<CodeEditor?>,
    modifier: Modifier = Modifier
) {
    var menuNode by remember { mutableStateOf<FileNode?>(null) }
    var renameNode by remember { mutableStateOf<FileNode?>(null) }
    var deleteNode by remember { mutableStateOf<FileNode?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    Box(modifier = modifier) {
        val root = vm.tree.value
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                vm.projectDir.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(12.dp)
            )
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            ) {
                item { FileTreeNode(root, vm, editorRef, 0, onLongClick = { menuNode = it }) }
            }
        }

        DropdownMenu(
            expanded = menuNode != null,
            onDismissRequest = { menuNode = null }
        ) {
            DropdownMenuItem(
                text = { Text("重命名") },
                onClick = {
                    menuNode?.let { renameText = it.name }
                    renameNode = menuNode
                    showRename = true
                    menuNode = null
                }
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    deleteNode = menuNode
                    showDelete = true
                    menuNode = null
                }
            )
        }

        if (showRename && renameNode != null) {
            AlertDialog(
                onDismissRequest = { showRename = false },
                title = { Text("重命名") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        placeholder = { Text("新名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        renameNode?.let { vm.renameFile(it.file, renameText) }
                        renameText = ""
                        renameNode = null
                        showRename = false
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        renameText = ""
                        renameNode = null
                        showRename = false
                    }) { Text("取消") }
                }
            )
        }

        if (showDelete && deleteNode != null) {
            AlertDialog(
                onDismissRequest = { showDelete = false },
                title = { Text("删除") },
                text = { Text("确定删除 ${deleteNode!!.name}？此操作不可恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteNode?.let { vm.deleteFile(it.file) }
                        deleteNode = null
                        showDelete = false
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        deleteNode = null
                        showDelete = false
                    }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun FileTreeNode(
    node: FileNode,
    vm: IDEViewModel,
    editorRef: androidx.compose.runtime.MutableState<CodeEditor?>,
    depth: Int,
    onLongClick: (FileNode) -> Unit
) {
    if (node.isDirectory) {
        val expanded = vm.expandedDirs.value.contains(node.file.absolutePath)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { vm.toggleDir(node.file.absolutePath) },
                    onLongClick = { onLongClick(node) }
                )
                .padding(start = (depth * 12 + 8).dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(node.name, style = MaterialTheme.typography.bodyMedium)
        }
        if (expanded) {
            node.children.forEach {
                FileTreeNode(it, vm, editorRef, depth + 1, onLongClick)
            }
        }
    } else {
        val selected = vm.currentFile.value == node.file
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        editorRef.value?.text?.toString()?.let { vm.saveContent(it) }
                        vm.openFile(node.file)
                    },
                    onLongClick = { onLongClick(node) }
                )
                .padding(start = (depth * 12 + 8).dp, top = 4.dp, end = 0.dp, bottom = 4.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent,
                    shape = MaterialTheme.shapes.small
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(node.name, style = MaterialTheme.typography.bodySmall)
        }
    }
}
