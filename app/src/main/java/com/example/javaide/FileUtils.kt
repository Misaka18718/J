package com.example.javaide

import java.io.File

/**
 * 文件 / 项目结构相关的工具方法。
 */
object FileUtils {

    /** 递归构建文件树（目录在前、文件在后，按名称排序）。 */
    fun buildTree(dir: File): FileNode {
        val children = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.map { f: File ->
                if (f.isDirectory) buildTree(f) else FileNode(f.name, f, false, emptyList())
            }
            ?: emptyList()
        return FileNode(dir.name, dir, true, children)
    }

    /**
     * 根据文件在 src 下的相对路径推导 package 名。
     * 例如 src/com/example/demo/Main.java -> com.example.demo
     */
    fun packageOf(projectDir: File, file: File): String {
        val src = File(projectDir, "src")
        val parent = file.parentFile ?: return ""
        val rel = try {
            src.toPath().relativize(parent.toPath()).toString()
        } catch (_: Exception) {
            ""
        }
        return rel.replace(File.separatorChar, '.').trim('.')
    }

    /** 新建 Java 文件时写出的默认内容（不含光标标记）。 */
    fun defaultFileContent(projectDir: File, file: File): String {
        val pkg = packageOf(projectDir, file)
        val cls = file.nameWithoutExtension
        val header = if (pkg.isNotEmpty()) "package $pkg;\n\n" else ""
        return "${header}public class $cls {\n    \n}\n"
    }

    /** 初始化工程目录（只建必要的 src / out 目录，不创建演示文件）。 */
    fun ensureSampleProject(projectDir: File) {
        File(projectDir, "src").mkdirs()
        File(projectDir, "out").mkdirs()
    }
}
