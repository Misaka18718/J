package com.example.javaide

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyv.java.compiler.JavaEngine
import com.xiaoyv.java.compiler.tools.exec.JavaProgramConsole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

class IDEViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext

    /** 工程根目录（沙箱内的私有目录，无需存储权限）。 */
    val projectDir: File =
        (context.getExternalFilesDir(null) ?: context.filesDir).resolve("JavaIDEProject")

    // ---------- UI 状态 ----------
    val tree = androidx.compose.runtime.mutableStateOf(FileUtils.buildTree(projectDir))
    val currentFile = androidx.compose.runtime.mutableStateOf<File?>(null)
    val isRunning = androidx.compose.runtime.mutableStateOf(false)
    val consoleExpanded = androidx.compose.runtime.mutableStateOf(true)
    val treeOpen = androidx.compose.runtime.mutableStateOf(true)
    val snippetQuery = androidx.compose.runtime.mutableStateOf("")
    val programConsole =
        androidx.compose.runtime.mutableStateOf<JavaProgramConsole?>(null)
    val expandedDirs =
        androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())

    /** 撤销 / 重做可用状态（由编辑器内容变化事件更新）。 */
    val canUndo = androidx.compose.runtime.mutableStateOf(false)
    val canRedo = androidx.compose.runtime.mutableStateOf(false)

    /** 程序化改写编辑器文本时的防递归开关（非 Compose 状态）。 */
    var applyingSnippet = false

    // ---------- 控制台输出 ----------
    private val _console = MutableStateFlow("")
    val console = _console.asStateFlow()
    private val buffer = StringBuilder()
    private val bufLock = ReentrantLock()
    private val MAX_LEN = 80_000

    init {
        FileUtils.ensureSampleProject(projectDir)
        currentFile.value = File(projectDir, "src/com/example/demo/Main.java")
        refreshTree()
    }

    // ---------- 文件树 ----------
    fun refreshTree() {
        tree.value = FileUtils.buildTree(projectDir)
    }

    fun toggleDir(path: String) {
        val set = expandedDirs.value
        expandedDirs.value = if (set.contains(path)) set - path else set + path
    }

    // ---------- 文件操作 ----------
    fun openFile(file: File) {
        currentFile.value = file
    }

    fun saveContent(text: String) {
        currentFile.value?.let { f: File ->
            if (!f.exists()) f.parentFile?.mkdirs()
            f.writeText(text)
        }
    }

    fun createSrc() {
        File(projectDir, "src").mkdirs()
        refreshTree()
        appendConsole("已创建 src/\n")
    }

    fun createOut() {
        File(projectDir, "out").mkdirs()
        refreshTree()
        appendConsole("已创建 out/\n")
    }

    /** 按包名创建目录，例如 "com.example.demo"。 */
    fun createPackage(pkg: String) {
        val parts = pkg.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            appendConsole("包名不能为空\n")
            return
        }
        var dir = File(projectDir, "src")
        parts.forEach { dir = File(dir, it).apply { mkdirs() } }
        refreshTree()
        appendConsole("已创建包路径：${dir.absolutePath}\n")
    }

    /** 在指定目录下新建 Java 文件并打开。 */
    fun createFile(name: String, dir: File) {
        val fileName = if (name.endsWith(".java")) name else "$name.java"
        val file = File(dir, fileName)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(FileUtils.defaultFileContent(projectDir, file))
        }
        refreshTree()
        openFile(file)
        appendConsole("已打开 $fileName\n")
    }

    /** 重命名文件或目录（保持在同一父目录下）。 */
    fun renameFile(file: File, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        val parent = file.parentFile ?: return
        val target = File(parent, name)
        if (target.exists()) {
            appendConsole("重命名失败：名称已存在\n")
            return
        }
        if (file.renameTo(target)) {
            if (currentFile.value == file) currentFile.value = target
            refreshTree()
            appendConsole("已重命名 -> $name\n")
        } else {
            appendConsole("重命名失败\n")
        }
    }

    /** 删除文件或目录（目录递归删除）。 */
    fun deleteFile(file: File) {
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (ok) {
            val cur = currentFile.value
            if (cur != null && (cur == file || cur.path.startsWith(file.path + File.separator))) {
                currentFile.value = null
                applySnippet = false
            }
            refreshTree()
            appendConsole("已删除：${file.name}\n")
        } else {
            appendConsole("删除失败\n")
        }
    }

    // ---------- 运行管线 ----------
    fun runCode(sourceText: String) {
        val srcRoot = File(projectDir, "src")
        if (!srcRoot.exists()) {
            appendConsole(">>> 请先创建 src 目录\n")
            return
        }
        currentFile.value?.let { f: File ->
            if (!f.exists()) f.parentFile?.mkdirs()
            f.writeText(sourceText)
        }

        viewModelScope.launch(Dispatchers.IO) {
            isRunning.value = true
            consoleExpanded.value = true
            appendConsole("\n>>> 编译中...\n")
            try {
                val outDir = File(projectDir, "out").apply { mkdirs() }
                val jar = JavaEngine.classCompiler.compile(srcRoot, outDir, null) { _, _ -> }
                appendConsole(">>> 编译成功：${jar.name}\n")
                appendConsole(">>> 转换为 Dex...\n")
                val dex = JavaEngine.dexCompiler.compile(jar, outDir)
                appendConsole(">>> 运行中：\n")
                val consoleHandle = JavaEngine.javaProgram.run(
                    dex,
                    emptyArray<String>(),
                    printOut = { appendConsole(it.toString()) },
                    printErr = { appendConsole(it.toString()) }
                )
                programConsole.value = consoleHandle
            } catch (e: Throwable) {
                appendConsole("\n>>> 运行失败：\n${e.message}\n")
            } finally {
                isRunning.value = false
            }
        }
    }

    fun stop() {
        runCatching { programConsole.value?.close() }
        programConsole.value = null
        appendConsole("\n>>> 已停止\n")
    }

    /** 向正在运行的程序写入标准输入（用于 Scanner / BufferedReader 等）。 */
    fun sendInput(text: String) {
        runCatching { programConsole.value?.inputStdin(text + "\n") }
    }

    // ---------- 片段提示 ----------
    fun setSnippetQuery(q: String) {
        snippetQuery.value = q
    }

    // ---------- 控制台 ----------
    fun appendConsole(s: String) {
        bufLock.withLock {
            buffer.append(s)
            if (buffer.length > MAX_LEN) {
                buffer.delete(0, buffer.length - MAX_LEN)
            }
        }
        _console.value = bufLock.withLock { buffer.toString() }
    }

    fun clearConsole() {
        bufLock.withLock { buffer.setLength(0) }
        _console.value = ""
    }

    fun toggleTree() {
        treeOpen.value = !treeOpen.value
    }

    fun toggleConsole() {
        consoleExpanded.value = !consoleExpanded.value
    }
}
