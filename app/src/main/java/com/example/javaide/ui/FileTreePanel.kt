package com.example.javaide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * 左侧可折叠目录树。文件节点支持：单击打开、长按弹出重命名/删除菜单。
 */
@Composable
fun FileTreePanel(
    vm: IDEViewModel,
    editorRef: androidx.compose.runtime.MutableState<CodeEditor?>,
    modifier: Modifier = Modifier
) {
    val root = vm.tree.value
    var renameNode by remember { mutableStateOf<FileNode?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteNode by remember { mutableStateOf<FileNode?>(null) }

    Column(
        modifier = modifier
            .fillMaxHeight()
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
            item {
                FileTreeNode(
                    root,
                    vm,
                    editorRef,
                    0,
                    onRename = { n ->
                        renameNode = n
                        renameText = n.name
                    },
                    onDelete = { deleteNode = it }
                )
            }
        }
    }

    // 重命名对话框
    if (renameNode != null) {
        AlertDialog(
            onDismissRequest = { renameNode = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = { Text("文件名，如 Hello") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        vm.renameFile(renameNode!!.file, renameText)
                        renameNode = null
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameNode = null }) { Text("取消") }
            }
        )
    }

    // 删除确认对话框
    if (deleteNode != null) {
        AlertDialog(
            onDismissRequest = { deleteNode = null },
            title = { Text("删除") },
            text = {
                Text(
                    "确定删除 ${deleteNode!!.name} 吗？" +
                        if (deleteNode!!.isDirectory) "（目录会递归删除，不可撤销）" else "（不可撤销）"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFile(deleteNode!!.file)
                    deleteNode = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteNode = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun FileTreeNode(
    node: FileNode,
    vm: IDEViewModel,
    editorRef: androidx.compose.runtime.MutableState<CodeEditor?>,
    depth: Int,
    onRename: (FileNode) -> Unit,
    onDelete: (FileNode) -> Unit
) {
    if (node.isDirectory) {
        val expanded = vm.expandedDirs.value.contains(node.file.absolutePath)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.toggleDir(node.file.absolutePath) }
                .padding(start = (depth * 12 + 8).dp, top = 4.dp, end = 0.dp, bottom = 4.dp),
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
                FileTreeNode(it, vm, editorRef, depth + 1, onRename, onDelete)
            }
        }
    } else {
        val selected = vm.currentFile.value == node.file
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            editorRef.value?.text?.toString()?.let { vm.saveContent(it) }
                            vm.openFile(node.file)
                        },
                        onLongClick = { menuOpen = true }
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
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = { menuOpen = false; onRename(node) }
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    onClick = { menuOpen = false; onDelete(node) }
                )
            }
        }
    }
}
