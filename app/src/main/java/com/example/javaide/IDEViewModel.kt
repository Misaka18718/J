package com.example.javaide

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyv.java.compiler.JavaEngine
import com.xiaoyv.java.compiler.tools.exec.JavaProgramHelper
import android.util.Log
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import dalvik.system.PathClassLoader
import java.io.OutputStream
import java.nio.ByteBuffer
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.lang.reflect.Method
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

/**
 * 运行句柄：v3.4 起，[IDEViewModel] 不再依赖 compiler-d8 的 [com.xiaoyv.java.compiler.tools.exec.JavaProgram.run]
 * （其内部 hardcode `optimizedDirectory = JavaEngineSetting.defaultCacheDir`，在本机会解析为
 * `/data/user/0/...` 符号链接形式，导致 DexClassLoader 构造即抛 `No such file or directory`）。
 * 改为自建 DexClassLoader 运行器，完全掌控路径与优化目录。
 */
interface RunHandle {
    /** 写入标准输入（供 Scanner / BufferedReader 等读取）。 */
    fun inputStdin(text: String)
    /** 停止程序并恢复 System 标准流。 */
    fun close()
}

class IDEViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext

    /** 设置持久化。 */
    private val prefs: SharedPreferences =
        context.getSharedPreferences("javaide_settings", Context.MODE_PRIVATE)

    /** 工作目录模式：private=应用私有目录（无需权限），public=公共存储。 */
    private fun privateDir(): File =
        (context.getExternalFilesDir(null) ?: context.filesDir).resolve("JavaIDEProject")

    /** 公共存储文件夹名（可自定义，存于 SharedPreferences）。 */
    val publicStoragePath = androidx.compose.runtime.mutableStateOf(
        prefs.getString("publicStoragePath", "JavaIDE_Workspace") ?: "JavaIDE_Workspace"
    )

    private fun publicDir(): File =
        File(Environment.getExternalStorageDirectory(), publicStoragePath.value)

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
        androidx.compose.runtime.mutableStateOf<RunHandle?>(null)
    val expandedDirs =
        androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())

    /** Toast 提示流：UI 侧收集后弹出，用于"创建 src/out/保存"等操作的明确用户反馈。 */
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast = _toast.asSharedFlow()
    private fun toast(msg: String) { _toast.tryEmit(msg) }

    /**
     * 运行时入口类选择（v3.0 增强）。
     *
     * 库的 [com.xiaoyv.java.compiler.tools.exec.JavaProgram.run] 在检测到入口类后，
     * 会在 Dispatchers.Main 上调用 `chooseMainClassToRun(classes, continuation)` 并挂起协程，
     * 等待我们 `continuation.resume(选中的类)`。
     *
     * - 只有 1 个入口类：直接 resume，无需弹窗。
     * - 有 ≥2 个入口类：把候选类名交给 [mainClassChoices] 驱动弹窗，并把协程
     *   [pendingMainContinuation] 暂存；用户在弹窗中选择后调用 [chooseMainClass] 恢复协程，
     *   真正运行所选入口（而非库默认的“第一个 main”）。
     */
    /** 弹窗数据源：非空时 IDEScreen 弹出“选择入口类”对话框。 */
    val mainClassChoices = androidx.compose.runtime.mutableStateOf<List<String>?>(null)
    /** 被挂起的协程 continuation（仅库回调内部使用，不暴露给 UI）。 */
    private var pendingMainContinuation: CancellableContinuation<String>? = null

    /** 用户在弹窗中确认运行某个入口类。 */
    fun chooseMainClass(cls: String) {
        mainClassChoices.value = null
        pendingMainContinuation?.let { if (it.isActive) it.resume(cls) }
        pendingMainContinuation = null
    }

    /** 用户取消入口类选择。 */
    fun cancelMainClass() {
        mainClassChoices.value = null
        pendingMainContinuation?.let { if (it.isActive) it.cancel() }
        pendingMainContinuation = null
    }

    /**
     * 构造入口类选择回调，供 [runDexDirectly] 在查询到主类后选择运行哪一个。
     * 显式标注参数类型以匹配 `((List<String>, CancellableContinuation<String>) -> Unit)`，
     * 避免 Kotlin 因类型推断失败而无法解析 `resume` / `resumeWithException`。
     */
    private fun chooseEntryClass(): (List<String>, CancellableContinuation<String>) -> Unit =
        { classes: List<String>, cont: CancellableContinuation<String> ->
            when {
                classes.isEmpty() -> cont.resumeWithException(
                    RuntimeException("未找到包含 main(String[] args) 方法的可执行类")
                )
                classes.size == 1 -> cont.resume(classes.first())
                else -> {
                    pendingMainContinuation = cont
                    mainClassChoices.value = classes
                }
            }
        }

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
        refreshTree()
        restoreTabState()
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
        saveTabState()
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
        saveTabState()
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
        saveTabState()
    }

    /** 持久化当前标签页状态（退出重进恢复）。 */
    private fun saveTabState() {
        val paths = openTabs.value.map { it.absolutePath }
        val active = activeTabFile()?.absolutePath ?: ""
        prefs.edit()
            .putString("savedTabPaths", paths.joinToString("\n"))
            .putString("savedActivePath", active)
            .apply()
    }

    /** 恢复上次退出时的标签页（跳过已删除的文件）。 */
    private fun restoreTabState() {
        val pathsStr = prefs.getString("savedTabPaths", "") ?: ""
        if (pathsStr.isBlank()) return
        val paths = pathsStr.split("\n").filter { it.isNotBlank() }
        val activePath = prefs.getString("savedActivePath", "") ?: ""
        for (path in paths) {
            val f = File(path)
            if (f.isFile && f.exists()) openFile(f)
        }
        if (activePath.isNotBlank()) {
            val idx = openTabs.value.indexOfFirst { it.absolutePath == activePath }
            if (idx >= 0) activeTab.value = idx
        }
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
    /**
     * 将 dex 复制到应用私有目录并返回该私有路径（使用 canonicalPath，彻底解析 /data/user/0 符号链接）。
     *
     * v3.4 根因：compiler-d8 的 [com.xiaoyv.java.compiler.tools.exec.JavaProgram.run] 内部
     * hardcode `optimizedDirectory = JavaEngineSetting.defaultCacheDir`
     * (= `GlobalUtils.getApp().filesDir + "/tmp/compiler"`)，在本机会解析为 `/data/user/0/<pkg>/...`
     * 符号链接形式；DexClassLoader 在该形式下构造即抛 `No such file or directory`(ENOENT)。
     *
     * 因此 v3.4 不再走库的 run()，改为自建 DexClassLoader 运行器（见 [runDexDirectly]），
     * 并把 dex 与优化目录都落到 canonical 后的 `/data/data/<pkg>/...` 真实路径。
     */
    private fun copyDexToPrivate(dexFile: File): File {
        // 落到 codeCacheDir（Android 指定的代码缓存区，SELinux 标签最稳妥，专为承载 dex 设计），
        // 不再放 filesDir 下自建子目录（部分 ROM 对 files 子目录的 dex 读取会因标签问题 ENOENT）。
        val baseDir = File(context.codeCacheDir.canonicalPath)
        val privateDir = File(baseDir, "dexrun").apply { mkdirs() }
        val target = File(privateDir, dexFile.name)
        appendConsole(">>> Dex 源路径：${dexFile.absolutePath}\n")
        appendConsole(">>> Dex 源 exists=${dexFile.exists()} canRead=${dexFile.canRead()} length=${dexFile.length()}\n")
        runCatching {
            dexFile.inputStream().use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }.onFailure {
            // 复制失败时退回到原始 dex（例如原始 dex 本来就位于私有目录）
            appendConsole("⚠ 复制 Dex 失败（已回退原始路径）：${it.message}\n")
            return dexFile
        }
        // 校验 dex 魔数（前 4 字节应为 "dex\n"），确认复制得到的是合法 dex 而非空文件/损坏内容
        val magic = ByteArray(4)
        val n = runCatching { target.inputStream().use { it.read(magic) } }.getOrElse { -1 }
        val magicOk = n == 4 &&
                magic[0] == 'd'.code.toByte() && magic[1] == 'e'.code.toByte() &&
                magic[2] == 'x'.code.toByte() && magic[3] == '\n'.code.toByte()
        appendConsole(">>> Dex 复制 → ${target.canonicalPath}\n")
        appendConsole(">>> Dex 大小=${target.length()} 魔数有效=${magicOk}\n")
        if (!magicOk) appendConsole("⚠ 警告：dex 魔数无效，可能不是合法 dex 文件\n")
        return target
    }

    /**
     * 从 dex 中提取所有包含 `main(String[])` 的类全名（即入口类列表）。
     * 复用库提供的 [JavaProgramHelper.queryMainFunctionList]——它是纯 dex 解析，
     * 与库的 run() 无关；v3.4 日志已证明它能正确找到主类（否则会报“未找到 main”而非 ENOENT）。
     */
    private suspend fun detectMainClassFromDex(dexFile: File): List<String> {
        return JavaProgramHelper.queryMainFunctionList(dexFile)
    }

    /**
     * v3.4 起：完全自建 DexClassLoader 运行器，**不再调用** compiler-d8 的 JavaProgram.run()。
     *
     * 1. dex 与优化目录使用 codeCacheDir（Android 指定的代码缓存区，SELinux 标签最稳）；
     * 2. 通过 [detectMainClassFromDex] 取主类，并复用多主类选择弹窗；
     * 3. 重定向 System.out/err/in 到控制台，支持 stdin；
     * 4. v3.8：加载器升级为「多策略 + 内存加载优先」。
     *    真机实测：dex 文件存在、可读、魔数有效、优化目录可写，但 DexClassLoader/PathClassLoader
     *    仍报 `No such file or directory`——根因是 ART 在生成优化产物（oat/odex）时被 SELinux 拦截，
     *    与 dex/jar 内容无关（连 Termux 合法 JAR 也相同失败）。
     *    故 v3.8 把 [InMemoryDexClassLoader]（API26+）放在最前：dex 直接读进 ByteBuffer，
     *    **不落盘、不生成 oat**，从根本上绕开 optimizedDir/SELinux 的 ENOENT。
     */
    private suspend fun runDexDirectly(dexFile: File, args: Array<String>): RunHandle {
        val mainClasses = detectMainClassFromDex(dexFile)

        // ===== 诊断日志（dex 可读性 / 优化目录权限 / SELinux 上下文，全部输出到 logcat + 控制台）=====
        val optDir = File(context.codeCacheDir.canonicalPath, "optimized").apply { mkdirs() }
        val cacheDir = context.codeCacheDir
        Log.d("JavaIDE", "Dex absolute  : ${dexFile.absolutePath}")
        Log.d("JavaIDE", "Dex canonical : ${dexFile.canonicalPath}")
        Log.d("JavaIDE", "Dex exists    : ${dexFile.exists()}")
        Log.d("JavaIDE", "Dex canRead   : ${dexFile.canRead()}")
        Log.d("JavaIDE", "Dex canWrite  : ${dexFile.canWrite()}")
        Log.d("JavaIDE", "Dex length    : ${dexFile.length()}")
        Log.d("JavaIDE", "Dex magic     : ${dexMagic(dexFile)}")
        Log.d("JavaIDE", "OptDir absolute: ${optDir.absolutePath}")
        Log.d("JavaIDE", "OptDir exists  : ${optDir.exists()}")
        Log.d("JavaIDE", "OptDir canWrite: ${optDir.canWrite()}")
        Log.d("JavaIDE", "CacheDir      : ${cacheDir.absolutePath} exists=${cacheDir.exists()}")
        Log.d("JavaIDE", "SDK_INT       : ${Build.VERSION.SDK_INT}")
        appendConsole(">>> Dex exists=${dexFile.exists()} canRead=${dexFile.canRead()} length=${dexFile.length()} magic=${dexMagic(dexFile)}\n")
        appendConsole(">>> 优化目录：${optDir.absolutePath} exists=${optDir.exists()} canWrite=${optDir.canWrite()}\n")
        appendConsole(">>> codeCacheDir：${cacheDir.absolutePath} exists=${cacheDir.exists()} SDK=${Build.VERSION.SDK_INT}\n")

        val mainClass = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String> { cont ->
                chooseEntryClass().invoke(mainClasses, cont)
            }
        }

        // 多策略加载：优先 InMemoryDexClassLoader（不落盘、不生成 oat，规避 SELinux 拦截），
        // 失败再依次回退到 jar 包装 + PathClassLoader / DexClassLoader / DexFile.loadDex。
        val method = try {
            loadMainMethod(dexFile, optDir.absolutePath, mainClass)
        } catch (e: Throwable) {
            Log.e("JavaIDE", "所有 Dex 加载策略均失败", e)
            appendConsole(">>> 所有 Dex 加载策略均失败：\n${throwableChain(e)}\n")
            throw e
        }
        appendConsole(">>> 运行中：\n")
        return DexRunHandle(method, args)
    }

    /**
     * 用多种类加载策略加载 dex/jar 并取出 main 方法，返回首个成功的 Method。
     * 策略顺序（前面的更稳）：
     *   0) InMemoryDexClassLoader(dex/jar 字节) —— API26+，内存加载，不生成 oat（v3.8 首选，根治 SELinux ENOENT）
     *   1) PathClassLoader(jar)               —— 包装成 jar，不指定优化目录
     *   2) DexClassLoader(raw dex + opt)
     *   3) DexClassLoader(jar + opt)
     *   4) InMemoryDexClassLoader(jar 字节)   —— 若裸 dex 字节加载失败，再试 jar 字节
     *   5) PathClassLoader(raw dex)
     *   6) DexFile.loadDex(dex + opt)        —— API24+ 兜底（同样会生成 oat，仅作最后手段）
     * 父加载器统一用应用自身 ClassLoader，便于访问 Android 框架类。
     * 每个失败策略都会把**完整异常链（含 Caused by）**打印到控制台，便于真机定位。
     */
    private fun loadMainMethod(dexFile: File, optDir: String, mainClass: String): Method {
        val parent = this.javaClass.classLoader
        val dexBytes = runCatching { dexFile.readBytes() }.getOrNull()
        val jar = runCatching { dexToJar(dexFile) }.getOrNull()
        val jarBytes = jar?.let { runCatching { it.readBytes() }.getOrNull() }

        val strategies = buildList {
            // 0) 内存加载（API26+）：dex 直接读进 ByteBuffer，不落盘、不生成 oat，彻底规避 optimizedDir/SELinux 的 ENOENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && dexBytes != null) {
                add("InMemoryDexClassLoader(dex bytes)" to {
                    makeInMemoryLoader(dexBytes, parent).loadClass(mainClass)
                        .getDeclaredMethod("main", Array<String>::class.java)
                })
            }
            if (jar != null) add("PathClassLoader(jar)" to {
                PathClassLoader(jar.canonicalPath, parent).loadClass(mainClass)
                    .getDeclaredMethod("main", Array<String>::class.java)
            })
            add("DexClassLoader(raw dex + opt)" to {
                DexClassLoader(dexFile.canonicalPath, optDir, null, parent).loadClass(mainClass)
                    .getDeclaredMethod("main", Array<String>::class.java)
            })
            if (jar != null) add("DexClassLoader(jar + opt)" to {
                DexClassLoader(jar.canonicalPath, optDir, null, parent).loadClass(mainClass)
                    .getDeclaredMethod("main", Array<String>::class.java)
            })
            if (jarBytes != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add("InMemoryDexClassLoader(jar bytes)" to {
                    makeInMemoryLoader(jarBytes, parent).loadClass(mainClass)
                        .getDeclaredMethod("main", Array<String>::class.java)
                })
            }
            add("PathClassLoader(raw dex)" to {
                PathClassLoader(dexFile.canonicalPath, parent).loadClass(mainClass)
                    .getDeclaredMethod("main", Array<String>::class.java)
            })
            if (dexBytes != null) add("DexFile.loadDex(dex + opt)" to {
                makeDexFileLoader(dexFile, optDir, parent, mainClass)
            })
        }

        var lastErr: Throwable? = null
        for ((name, make) in strategies) {
            try {
                val m = make()
                Log.d("JavaIDE", "加载策略成功：$name")
                appendConsole(">>> 加载策略：$name\n")
                return m
            } catch (e: Throwable) {
                lastErr = e
                Log.w("JavaIDE", "加载策略失败：$name", e)
                appendConsole(">>>   策略失败：$name\n${throwableChain(e)}\n")
            }
        }
        throw lastErr ?: RuntimeException("所有 Dex 加载策略均失败")
    }

    /** 通过反射构造 InMemoryDexClassLoader，避免 API<26 设备上类引用导致的 VerifyError。 */
    private fun makeInMemoryLoader(bytes: ByteArray, parent: ClassLoader): ClassLoader {
        val buf = ByteBuffer.wrap(bytes)
        val clazz = Class.forName("dalvik.system.InMemoryDexClassLoader")
        val ctor = clazz.getConstructor(ByteBuffer::class.java, ClassLoader::class.java)
        return ctor.newInstance(buf, parent) as ClassLoader
    }

    /** 用 DexFile.loadDex 直接加载 dex 并取出主类（API24+ 兜底，会生成 oat，仅最后手段）。 */
    private fun makeDexFileLoader(dexFile: File, optDir: String, parent: ClassLoader, mainClass: String): Method {
        val df = DexFile.loadDex(dexFile.canonicalPath, optDir, 0)
            ?: throw RuntimeException("DexFile.loadDex 返回 null")
        val clazz = df.loadClass(mainClass, parent)
        return clazz.getDeclaredMethod("main", Array<String>::class.java)
    }

    /** 读取 dex 魔数（前 4 字节，应为 "dex\n"），用于诊断。 */
    private fun dexMagic(f: File): String {
        val m = ByteArray(4)
        val n = runCatching { f.inputStream().use { it.read(m) } }.getOrElse { -1 }
        if (n != 4) return "读取失败($n)"
        return m.map { it.toInt().toChar() }.joinToString("") { if (it.isISOControl()) "<0x%02X>".format(it.code) else it.toString() }
    }

    /** 把异常的完整链（含 Caused by）格式化为字符串，保证真机能看到根因。 */
    private fun throwableChain(e: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = e
        var depth = 0
        while (cur != null) {
            if (depth > 0) sb.append("Caused by: ")
            sb.append(cur.toString()).append("\n")
            cur.stackTrace.take(15).forEach { sb.append("    at ").append(it).append("\n") }
            cur = cur.cause
            if (++depth > 6) break
        }
        return sb.toString()
    }

    /** 把裸 .dex 重新打包成 .jar（entry=classes.dex），放到 codeCacheDir/jarwrap/，规避部分 ROM 对裸 .dex 的 ENOENT。 */
    private fun dexToJar(dexFile: File): File {
        val out = File(File(context.codeCacheDir.canonicalPath, "jarwrap"), "classes.jar").apply {
            parentFile?.mkdirs()
        }
        JarOutputStream(out.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("classes.dex"))
            dexFile.inputStream().use { it.copyTo(jos) }
            jos.closeEntry()
        }
        Log.d("JavaIDE", "dex wrapped to jar: ${out.absolutePath} length=${out.length()}")
        return out
    }

    /**
     * v3.4 直接运行句柄：在独立 IO 作用域执行 main，重定向 System 标准流到控制台，支持 stdin。
     */
    private inner class DexRunHandle(
        private val mainMethod: Method,
        private val args: Array<String>
    ) : RunHandle {
        private val pipedOut = PipedOutputStream()
        private val pipedIn = PipedInputStream(pipedOut)
        private val origOut = System.out
        private val origErr = System.err
        private val origIn = System.`in`
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.IO)

        init {
            System.setIn(pipedIn)
            System.setOut(PrintStream(ConsoleOut(false), true))
            System.setErr(PrintStream(ConsoleOut(true), true))
            scope.launch {
                runCatching { mainMethod.invoke(null, args) }
                    .onFailure {
                        val msg = it.message ?: it.javaClass.simpleName
                        appendConsole("\n>>> 程序异常终止：\n$msg\n")
                    }
            }
        }

        private inner class ConsoleOut(private val isErr: Boolean) : OutputStream() {
            private val buf = StringBuilder()
            @Synchronized override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
                buf.append(String(b, off, len, Charsets.UTF_8))
                var i = buf.indexOf('\n')
                while (i >= 0) {
                    appendConsole(buf.substring(0, i + 1))
                    buf.delete(0, i + 1)
                    i = buf.indexOf('\n')
                }
            }
        }

        override fun inputStdin(text: String) {
            runCatching { pipedOut.write(text.toByteArray()); pipedOut.flush() }
        }

        override fun close() {
            runCatching { pipedOut.close() }
            runCatching { job.cancel() }
            System.setOut(origOut)
            System.setErr(origErr)
            System.setIn(origIn)
        }
    }

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
                // 复制到私有目录，避免运行期读取外部存储 dex 触发 “No such file or directory”
                val runDex = copyDexToPrivate(dex)
                appendConsole(">>> 运行中：\n")
                val consoleHandle = runDexDirectly(runDex, emptyArray())
                programConsole.value = consoleHandle
            } catch (e: Throwable) {
                Log.e("JavaIDE", "运行失败", e)
                appendConsole("\n>>> 运行失败：\n${throwableChain(e)}\n")
            } finally {
                isRunning.value = false
            }
        }
    }

    /** 运行外部 .jar 文件：Dex 化后执行（不经过 javac 编译）。 */
    fun runJar(jarPath: String) {
        val jarFile = File(jarPath)
        appendConsole(">>> JAR 路径：$jarPath exists=${jarFile.exists()} length=${jarFile.length()}\n")
        if (!jarFile.exists() || jarFile.length() == 0L) {
            appendConsole(">>> JAR 文件不存在或为空：$jarPath\n")
            return
        }
        // 校验是否为合法 zip/jar：d8 对损坏的 jar 只会笼统报 "error in opening zip file" / "Compilation failed to complete"
        runCatching { ZipFile(jarFile) }.onFailure {
            appendConsole(">>> JAR 文件无效，请重新生成（${it.message}）\n")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            isRunning.value = true
            consoleExpanded.value = true
            appendConsole("\n>>> 转换 JAR 为 Dex：${jarFile.name}\n")
            try {
                val outDir = File(projectDir, "out").apply { mkdirs() }
                val dex = JavaEngine.dexCompiler.compile(jarFile, outDir)
                val runDex = copyDexToPrivate(dex)
                val consoleHandle = runDexDirectly(runDex, emptyArray())
                programConsole.value = consoleHandle
            } catch (e: Throwable) {
                Log.e("JavaIDE", "运行 JAR 失败", e)
                appendConsole("\n>>> 运行 JAR 失败：\n${throwableChain(e)}\n")
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

    fun setPublicStoragePath(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == publicStoragePath.value) return
        publicStoragePath.value = trimmed
        prefs.edit().putString("publicStoragePath", trimmed).apply()
        // 如果当前已在公共存储模式，自动迁移到新路径
        if (workingDirMode.value == "public") {
            applyWorkingDir("public")
        }
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
    /**
     * 将 src 编译并打包为 .jar 写入 jars/（可选入口类写入 Main-Class 清单）。
     *
     * v3.7 重构：不再直接 [File.copyTo] 库返回的 classes.jar。
     * 库在打包时会先 `createAndCleanFile` 创建一个**空**的 classes.jar，再写入条目；
     * 一旦条目为空（例如 zip 步骤异常）就会产出 0 字节/无效 jar（“error in opening zip file”）。
     * 这里改为**自行用 JarOutputStream 重建 jar**：从编译产物（jar / classes 目录 / 单 .class 文件，
     * 按可靠性回退）逐一写入条目，过程中输出每个条目名，打包后用 ZipFile 校验有效性。
     */
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
                // 库的约定：编译后的 .class 落在 buildDir/classes，jar 产物在 buildDir/jar/classes.jar
                val classesDir = File(buildDir, "classes")
                appendConsole(">>> 编译 *.java ...\n")
                val compiled = JavaEngine.classCompiler.compile(srcRoot, buildDir, null) { _, _ -> }
                appendConsole(">>> 编译产物：${compiled.absolutePath} exists=${compiled.exists()} isDir=${compiled.isDirectory} size=${compiled.length()}\n")
                val jarsDir = File(projectDir, "jars").apply { mkdirs() }
                val outFile = File(jarsDir, jarName)
                writeJar(compiled, classesDir, outFile, mainClass.trim())
                appendConsole(">>> 打包成功：${outFile.absolutePath}\n")
            } catch (e: Throwable) {
                appendConsole("\n>>> 打包失败：\n${e.stackTraceToString()}\n")
            }
        }
    }

    /**
     * 把编译产物 [compiled] 重建为合法 jar 写入 [outFile]。
     * 条目来源按可靠性回退：① compiled 自身非空 jar/zip → ② buildDir/classes 目录 → ③ 单 .class 文件。
     * 每个被写入的条目都会打印到控制台；可选 [mainClass] 写入 Main-Class 清单。
     */
    private fun writeJar(compiled: File, classesDir: File, outFile: File, mainClass: String) {
        val sources = resolveJarSources(compiled, classesDir)
        if (sources.isEmpty()) {
            appendConsole("⚠ 没有可打包的 .class 条目，跳过（可能编译未产出任何类）\n")
            return
        }
        val manifest = if (mainClass.isBlank()) null else Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Main-Class", mainClass)
        }
        var count = 0
        JarOutputStream(outFile.outputStream().buffered(), manifest).use { out ->
            for ((name, bytes) in sources) {
                if (name.equals("META-INF/MANIFEST.MF", true)) continue
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
                out.closeEntry()
                count++
                appendConsole(">>>   打包：$name (${bytes.size} 字节)\n")
            }
        }
        appendConsole(">>> 共写入 $count 个条目\n")
        validateJar(outFile)
    }

    /** 决定 jar 的条目来源（名称 + 字节），按可靠性回退。 */
    private fun resolveJarSources(compiled: File, classesDir: File): List<Pair<String, ByteArray>> {
        // ① compiled 自身是 jar/zip（库目录编译返回 buildDir/jar/classes.jar）
        if (compiled.isFile && (compiled.extension.equals("jar", true) || compiled.extension.equals("zip", true))) {
            val fromJar = readJarEntries(compiled)
            if (fromJar.isNotEmpty()) return fromJar
            appendConsole(">>> 编译产物 jar 为空，回退到 classes 目录\n")
        }
        // ② buildDir/classes 目录（编译产物的真源，最可靠）
        if (classesDir.isDirectory) {
            val fromDir = readDirEntries(classesDir)
            if (fromDir.isNotEmpty()) return fromDir
        }
        // ③ 单 .class 文件（单文件编译时 compiled 即 .class 路径）
        if (compiled.isFile) {
            return listOf(compiled.name to compiled.readBytes())
        }
        return emptyList()
    }

    /** 读取 jar/zip 全部非目录条目为 (条目名, 字节)。 */
    private fun readJarEntries(jar: File): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        JarFile(jar).use { jf ->
            jf.entries().asSequence().forEach { e ->
                if (e.isDirectory) return@forEach
                jf.getInputStream(e).use { out.add(e.name to it.readBytes()) }
            }
        }
        return out
    }

    /** 递归读取目录内全部文件为 (相对路径, 字节)。 */
    private fun readDirEntries(dir: File): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val name = f.relativeTo(dir).invariantSeparatorsPath
            if (name.isNotBlank()) out.add(name to f.readBytes())
        }
        return out
    }

    /** 用 ZipFile 打开并统计条目，验证 jar 有效性（空包会给出明确告警）。 */
    private fun validateJar(jar: File) {
        runCatching {
            ZipFile(jar).use { zf ->
                val entries = zf.entries().asSequence().toList()
                appendConsole(">>> 校验 JAR：路径=${jar.absolutePath} 大小=${jar.length()} 条目=${entries.size}\n")
                if (entries.isEmpty()) {
                    appendConsole("⚠ 警告：JAR 没有任何条目（空包）！\n")
                } else {
                    entries.take(12).forEach { appendConsole(">>>   校验条目：${it.name}\n") }
                }
            }
        }.onFailure {
            appendConsole("⚠ JAR 校验失败（文件可能被损坏）：${it.message}\n")
        }
    }
}
