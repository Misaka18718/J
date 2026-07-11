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
    /** 加载/切换文件时的标记：避免把文件内容当成用户输入去触发片段提示与内容回写。 */
    var loadingFile = false

    // ---------- 多标签页 ----------
    /** 已打开的标签页（按顺序），元素为对应文件。 */
    val openTabs = androidx.compose.runtime.mutableStateOf<List<File>>(emptyList())
    /** 当前激活的标签页索引（无标签页时为 -1）。 */
    val activeTab = androidx.compose.runtime.mutableStateOf(-1)
    /** 每个标签页在内存中的最新文本（编辑器是唯一写入源，每次内容变化回写）。 */
    private val tabContent = HashMap<File, String>()
    /** 每个标签页已“保存到磁盘”的文本基线，用于判断 dirty。 */
    private val tabSaved = HashMap<File, String>()
    /** 每个标签页是否有未保存修改。 */
    private val tabDirty = HashMap<File, Boolean>()

    // ---------- 控制台输出 ----------
    private val _console = MutableStateFlow("")
    val console = _console.asStateFlow()
    private val buffer = StringBuilder()
    private val bufLock = ReentrantLock()
    private val MAX_LEN = 80_000

    init {
        FileUtils.ensureSampleProject(projectDir)
        val sample = File(projectDir, "src/com/example/demo/Main.java")
        if (sample.exists()) openFile(sample)
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

    // ---------- 多标签页 ----------
    /** 打开文件：已打开则切换到对应标签页，否则新建标签页并激活。 */
    fun openFile(file: File) {
        commitActiveTab()
        val idx = openTabs.value.indexOf(file)
        if (idx >= 0) {
            activeTab.value = idx
        } else {
            val disk = if (file.exists()) file.readText() else ""
            tabContent[file] = disk
            tabSaved[file] = disk
            tabDirty[file] = false
            val list = openTabs.value.toMutableList().apply { add(file) }
            activeTab.value = list.lastIndex
            openTabs.value = list
        }
        syncActive()
    }

    /** 把当前激活标签页的编辑器文本回写到内存缓存（供切换/关闭时保留）。 */
    fun updateActiveContent(text: String) {
        val f = openTabs.value.getOrNull(activeTab.value) ?: return
        tabContent[f] = text
        tabDirty[f] = text != (tabSaved[f] ?: "")
    }

    fun activeTabContent(): String {
        val f = openTabs.value.getOrNull(activeTab.value) ?: return ""
        return tabContent[f] ?: ""
    }

    fun activeTabFile(): File? = openTabs.value.getOrNull(activeTab.value)

    fun setActiveTab(index: Int) {
        if (index !in openTabs.value.indices) return
        commitActiveTab()
        activeTab.value = index
        syncActive()
    }

    fun closeTab(index: Int) {
        val list = openTabs.value.toMutableList()
        if (index !in list.indices) return
        commitActiveTab()
        val file = list[index]
        // 关闭非激活标签页时，也把它的内存内容落盘，避免未保存修改丢失
        tabContent[file]?.let { txt ->
            if (!file.exists()) file.parentFile?.mkdirs()
            file.writeText(txt)
            tabSaved[file] = txt
            tabDirty[file] = false
        }
        list.removeAt(index)
        openTabs.value = list
        tabContent.remove(file); tabDirty.remove(file); tabSaved.remove(file)
        if (activeTab.value >= list.size) activeTab.value = list.lastIndex
        else if (activeTab.value > index) activeTab.value -= 1
        syncActive()
    }

    /** 把激活标签页的内存内容落盘（切换/关闭前调用，避免丢失）。不产生控制台输出。 */
    private fun commitActiveTab() {
        val f = openTabs.value.getOrNull(activeTab.value) ?: return
        val txt = tabContent[f] ?: return
        if (!f.exists()) f.parentFile?.mkdirs()
        f.writeText(txt)
        tabSaved[f] = txt
        tabDirty[f] = false
    }

    private fun syncActive() {
        currentFile.value = openTabs.value.getOrNull(activeTab.value)
    }

    // ---------- 文件操作 ----------
    fun saveContent(text: String) {
        val f = currentFile.value ?: return
        if (!f.exists()) f.parentFile?.mkdirs()
        f.writeText(text)
        tabContent[f] = text
        tabSaved[f] = text
        tabDirty[f] = false
        appendConsole("已保存：${f.name}\n")
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
            val idx = openTabs.value.indexOf(file)
            if (idx >= 0) {
                val list = openTabs.value.toMutableList().apply { set(idx, target) }
                openTabs.value = list
                tabContent[target] = tabContent.remove(file) ?: (if (target.exists()) target.readText() else "")
                tabSaved[target] = tabSaved.remove(file) ?: (if (target.exists()) target.readText() else "")
                tabDirty[target] = tabDirty.remove(file) ?: false
            }
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
            val list = openTabs.value.toMutableList()
            val removed = list.filter { it == file || it.path.startsWith(file.path + File.separator) }
            list.removeAll(removed)
            openTabs.value = list
            removed.forEach {
                tabContent.remove(it); tabDirty.remove(it); tabSaved.remove(it)
            }
            if (activeTab.value >= list.size) activeTab.value = list.lastIndex
            syncActive()
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
