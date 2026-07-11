package com.example.javaide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.javaide.FileNode
import com.example.javaide.IDEViewModel
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 左侧可折叠目录树。
 */
@Composable
fun FileTreePanel(
    vm: IDEViewModel,
    editorRef: androidx.compose.runtime.MutableState<CodeEditor?>,
    modifier: Modifier = Modifier
) {
    val root = vm.tree.value
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
            item { FileTreeNode(root, vm, editorRef, 0) }
        }
    }
}

@Composable
private fun FileTreeNode(
    node: FileNode,
    vm: IDEViewModel,
    editorRef: androidx.compose.runtime.MutableState<CodeEditor?>,
    depth: Int
) {
    if (node.isDirectory) {
        val expanded = vm.expandedDirs.value.contains(node.file.absolutePath)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.toggleDir(node.file.absolutePath) }
                .padding(start = (depth * 12 + 8).dp, vertical = 4.dp),
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
            node.children.forEach { FileTreeNode(it, vm, editorRef, depth + 1) }
        }
    } else {
        val selected = vm.currentFile.value == node.file
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    editorRef.value?.text?.toString()?.let { vm.saveContent(it) }
                    vm.openFile(node.file)
                }
                .padding(start = (depth * 12 + 8).dp, vertical = 4.dp)
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
