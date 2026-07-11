package com.example.javaide

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyv.java.compiler.JavaEngine
import com.xiaoyv.java.compiler.tools.exec.JavaProgramConsole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

class IDEViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext

    /** 设置持久化。 */
    private val prefs: SharedPreferences =
        context.getSharedPreferences("javaide_settings", Context.MODE_PRIVATE)

    /** 工作目录模式：private=应用私有目录（无需权限），public=公共存储。 */
    private fun privateDir(): File =
        (context.getExternalFilesDir(null) ?: context.filesDir).resolve("JavaIDEProject")

    private fun publicDir(): File =
        File(Environment.getExternalStorageDirectory(), "JavaIDE_Workspace")

    /** 工程根目录（可切换私有/公共存储）。 */
    var projectDir: File = if ((prefs.getString("workingDirMode", "private")
            ?: "private") == "public"
    ) publicDir() else privateDir()

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

    /** Toast 提示流：UI 侧收集后弹出，用于“创建 src/out/保存”等操作的明确用户反馈。 */
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast = _toast.asSharedFlow()
    private fun toast(msg: String) { _toast.tryEmit(msg) }

    /** 新建文件后期望的光标位置（char index）；-1 表示不定位。由编辑器在加载内容后消费。 */
    val pendingCursorIndex = androidx.compose.runtime.mutableStateOf(-1)

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

    // ---------- 设置（持久化到 SharedPreferences） ----------
    val nightMode = androidx.compose.runtime.mutableStateOf(prefs.getBoolean("nightMode", false))
    val editorFontSize =
        androidx.compose.runtime.mutableStateOf(prefs.getFloat("editorFontSize", 14f))
    val consoleMaxLines =
        androidx.compose.runtime.mutableStateOf(prefs.getInt("consoleMaxLines", 2000))
    val autoSave = androidx.compose.runtime.mutableStateOf(prefs.getBoolean("autoSave", true))
    val showLineNumbers =
        androidx.compose.runtime.mutableStateOf(prefs.getBoolean("showLineNumbers", true))
    val workingDirMode =
        androidx.compose.runtime.mutableStateOf(prefs.getString("workingDirMode", "private")
            ?: "private")

    /** 新建文件时选中的目标目录（默认 null，落回 src 或当前文件目录）。 */
    val selectedDir = androidx.compose.runtime.mutableStateOf<File?>(null)

    /** 自动保存的防抖任务（最后一次输入后延迟落盘）。 */
    private var autoSaveJob: Job? = null

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
        // 只要内容与“上一次显式保存/打开时”不一致，就标记为脏（用于关闭/切换时的确认保存）。
        // 注意：这里不再因自动保存而清除脏标记——否则“未保存退出确认”永远不会触发。
        tabDirty[f] = text != (tabSaved[f] ?: "")
        // 自动保存：最后一次改动后延迟落盘，避免每次按键都写磁盘
        if (autoSave.value) {
            autoSaveJob?.cancel()
            val file = f
            autoSaveJob = viewModelScope.launch(Dispatchers.IO) {
                delay(800)
                if (!file.exists()) file.parentFile?.mkdirs()
                file.writeText(text)
                tabSaved[file] = text
                // 仅更新磁盘基线，不清空 tabDirty：确认保存对话框仍应由用户显式保存触发
            }
        }
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

    /** 判断指定标签页是否存在未保存修改（用于关闭前的确认保存）。 */
    fun isTabDirty(index: Int): Boolean {
        val f = openTabs.value.getOrNull(index) ?: return false
        return tabDirty[f] ?: false
    }

    /** 将指定标签页的当前内存内容落盘（不关闭），并标记为已保存。 */
    fun saveTab(index: Int) {
        val f = openTabs.value.getOrNull(index) ?: return
        val txt = tabContent[f] ?: return
        if (!f.exists()) f.parentFile?.mkdirs()
        f.writeText(txt)
        tabSaved[f] = txt
        tabDirty[f] = false
    }

    /**
     * 关闭标签页。`save = true` 时先落盘再关闭（对应“保存”），
     * `save = false` 时直接丢弃该标签页的未保存修改（对应“不保存”）。
     */
    fun closeTab(index: Int, save: Boolean = true) {
        val list = openTabs.value.toMutableList()
        if (index !in list.indices) return
        commitActiveTab()
        val file = list[index]
        if (save) {
            // 关闭前把该标签页的内存内容落盘，避免未保存修改丢失
            tabContent[file]?.let { txt ->
                if (!file.exists()) file.parentFile?.mkdirs()
                file.writeText(txt)
                tabSaved[file] = txt
            }
        }
        tabDirty[file] = false
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
        val f = currentFile.value ?: run {
            toast("没有可保存的文件")
            return
        }
        try {
            if (!f.exists()) f.parentFile?.mkdirs()
            f.writeText(text)
            tabContent[f] = text
            tabSaved[f] = text
            tabDirty[f] = false
            appendConsole("已保存：${f.name}\n")
            toast("已保存：${f.name}")
        } catch (e: Throwable) {
            appendConsole("保存失败：${e.message}\n")
            toast("保存失败：${e.message}")
        }
    }

    fun createSrc() {
        val dir = File(projectDir, "src")
        when {
            dir.exists() -> {
                refreshTree()
                toast("src 已存在")
            }
            dir.mkdirs() -> {
                refreshTree()
                appendConsole("已创建 src/\n")
                toast("已创建 src/ 目录")
            }
            else -> {
                appendConsole("创建 src/ 失败\n")
                toast("创建 src/ 失败")
            }
        }
    }

    fun createOut() {
        val dir = File(projectDir, "out")
        when {
            dir.exists() -> {
                refreshTree()
                toast("out 已存在")
            }
            dir.mkdirs() -> {
                refreshTree()
                appendConsole("已创建 out/\n")
                toast("已创建 out/ 目录")
            }
            else -> {
                appendConsole("创建 out/ 失败\n")
                toast("创建 out/ 失败")
            }
        }
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

    /** 在指定目录下新建 Java 文件并打开。自动生成模板，并把光标定位到类体空白行（缩进一级）。 */
    fun createFile(name: String, dir: File) {
        val fileName = if (name.endsWith(".java")) name else "$name.java"
        val file = File(dir, fileName)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val content = FileUtils.defaultFileContent(projectDir, file)
            file.writeText(content)
            // 光标定位到 public class 两个括号之间的空白行、缩进之后：
            // 找到 "{<换行>"，其后为类体首行（已含一级缩进），光标落在缩进之后。
            val brace = content.indexOf("{\n")
            pendingCursorIndex.value = if (brace >= 0) brace + 2 + 4 else content.length
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
            // 按“最大行数”裁剪：超出时丢弃最旧的行
            val maxLines = consoleMaxLines.value
            if (maxLines > 0) {
                var lines = 1
                for (c in buffer) if (c == '\n') lines++
                if (lines > maxLines) {
                    val target = lines - maxLines
                    var removed = 0
                    var i = 0
                    while (removed < target && i < buffer.length) {
                        if (buffer[i] == '\n') {
                            removed++
                            i++
                        } else {
                            i++
                        }
                    }
                    buffer.delete(0, i)
                }
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

    // ---------- 设置操作 ----------
    fun setNightMode(v: Boolean) {
        nightMode.value = v
        prefs.edit().putBoolean("nightMode", v).apply()
    }

    fun setEditorFontSize(sp: Float) {
        val size = sp.coerceIn(8f, 40f)
        editorFontSize.value = size
        prefs.edit().putFloat("editorFontSize", size).apply()
    }

    fun setConsoleMaxLines(n: Int) {
        val v = n.coerceAtLeast(100)
        consoleMaxLines.value = v
        prefs.edit().putInt("consoleMaxLines", v).apply()
    }

    fun setAutoSave(v: Boolean) {
        autoSave.value = v
        prefs.edit().putBoolean("autoSave", v).apply()
    }

    fun setShowLineNumbers(v: Boolean) {
        showLineNumbers.value = v
        prefs.edit().putBoolean("showLineNumbers", v).apply()
    }

    /** 供新建文件对话框使用的目标目录：优先 selectedDir，其次当前文件目录，最后 src。 */
    fun targetDirForNewFile(): File {
        selectedDir.value?.let { if (it.exists()) return it }
        currentFile.value?.parentFile?.let { if (it.exists()) return it }
        return File(projectDir, "src").apply { mkdirs() }
    }

    // ---------- 工作目录切换 ----------
    /** 切换工作目录（调用方需先确保已获得公共存储权限）。 */
    fun applyWorkingDir(mode: String) {
        val newDir = if (mode == "public") publicDir() else privateDir()
        if (newDir.absolutePath == projectDir.absolutePath) {
            workingDirMode.value = mode
            prefs.edit().putString("workingDirMode", mode).apply()
            return
        }
        try {
            if (newDir.exists()) newDir.deleteRecursively()
            copyDir(projectDir, newDir)
        } catch (e: Throwable) {
            appendConsole(">>> 工作目录迁移失败：${e.message}\n")
        }
        projectDir = newDir
        workingDirMode.value = mode
        prefs.edit().putString("workingDirMode", mode).apply()
        // 重置标签页与状态，重新初始化示例工程
        openTabs.value = emptyList()
        activeTab.value = -1
        tabContent.clear(); tabSaved.clear(); tabDirty.clear()
        currentFile.value = null
        FileUtils.ensureSampleProject(projectDir)
        refreshTree()
        appendConsole(">>> 工作目录已切换：${newDir.absolutePath}\n")
    }

    private fun copyDir(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyDir(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            FileInputStream(src).use { input -> FileOutputStream(dst).use { output -> input.copyTo(output) } }
        }
    }

    // ---------- JAR 打包（主目标） ----------
    /** 将 src 编译打包为 .jar 写入 jars/（可选入口类写入 Main-Class 清单）。 */
    fun packageJar(name: String, mainClass: String) {
        val jarName = if (name.isBlank()) "app.jar"
        else if (name.endsWith(".jar")) name else "$name.jar"
        viewModelScope.launch(Dispatchers.IO) {
            appendConsole("\n>>> 正在打包 JAR：$jarName\n")
            try {
                val srcRoot = File(projectDir, "src")
                if (!srcRoot.exists()) {
                    appendConsole(">>> 请先创建 src 目录\n")
                    return@launch
                }
                val buildDir = File(projectDir, "build").apply { mkdirs() }
                appendConsole(">>> 编译 *.java -> classes.jar ...\n")
                val compiled = JavaEngine.classCompiler.compile(srcRoot, buildDir, null) { _, _ -> }
                val jarsDir = File(projectDir, "jars").apply { mkdirs() }
                val outFile = File(jarsDir, jarName)
                if (mainClass.isBlank()) {
                    compiled.copyTo(outFile, overwrite = true)
                } else {
                    repackageWithMain(compiled, outFile, mainClass.trim())
                }
                appendConsole(">>> 打包成功：${outFile.absolutePath}\n")
            } catch (e: Throwable) {
                appendConsole("\n>>> 打包失败：\n${e.stackTraceToString()}\n")
            }
        }
    }

    /** 读取已有 jar，重写时加入 Main-Class 清单。 */
    private fun repackageWithMain(src: File, dst: File, mainClass: String) {
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Main-Class", mainClass)
        }
        JarFile(src).use { jar ->
            JarOutputStream(FileOutputStream(dst), manifest).use { out ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.equals("META-INF/MANIFEST.MF", true)) continue
                    jar.getInputStream(entry).use { input ->
                        out.putNextEntry(JarEntry(entry.name))
                        input.copyTo(out)
                        out.closeEntry()
                    }
                }
            }
        }
    }
}
