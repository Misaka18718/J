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
            ?.map { f ->
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

    /** 初始化示例工程，保证首次打开就有内容可运行。 */
    fun ensureSampleProject(projectDir: File) {
        val src = File(projectDir, "src").apply { mkdirs() }
        val demoPkg = File(src, "com/example/demo").apply { mkdirs() }
        val main = File(demoPkg, "Main.java")
        if (!main.exists()) {
            main.writeText(
                """
                package com.example.demo;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println("Hello from Java IDE!");
                        int sum = 0;
                        for (int i = 1; i <= 5; i++) {
                            sum += i;
                        }
                        System.out.println("1..5 求和 = " + sum);
                    }
                }
                """.trimIndent() + "\n"
            )
        }
        File(projectDir, "out").mkdirs()
    }
}
