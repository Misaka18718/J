package com.example.javaide

import java.io.File

/**
 * 文件树节点：目录或普通文件。
 */
data class FileNode(
    val name: String,
    val file: File,
    val isDirectory: Boolean,
    val children: List<FileNode>
)
