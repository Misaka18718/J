# Java IDE for Android

一个运行在 Android 上的轻量 Java 开发环境，用 Kotlin + Jetpack Compose 编写。
核心能力：

- **代码编辑器**：基于 [Sora Editor](https://github.com/Rosemoe/sora-editor)，带 Java 语法高亮、自动补全、等宽字体。
- **快捷提示词（代码片段）**：输入触发词（如 `psvm`）即弹出候选列表，点选后展开为模板代码，光标自动落到 `$CARET$` 位置。内置 `psvm / sout / sos / fori / foreach / if / el / wh / sw / try / psf / classn / soutv`。
- **快捷创建工程结构**：一键创建 `src`、`out`，按包名（如 `com.example.demo`）创建多级 `package` 目录，以及新建 Java 文件。
- **编辑辅助**：顶栏提供 **撤销 / 重做** 按钮（移动端无 Ctrl+Z）；文件树中 **长按文件** 可重命名或删除。
- **试运行**：编译 (`ECJ`) → 转 Dex (`D8`) → 在 ART 上反射运行 `main` 方法，并把 `System.out / System.err` 重定向到控制台；支持向 `System.in` 输入（供 `Scanner` 等读取）。
- **清晰布局**：
  - 左侧可折叠目录树；
  - 屏幕上方约 **1/3** 为控制台，下方 **2/3** 为源代码；
  - 控制台在不运行/手动折叠时收成顶部一条工具条，点右上角箭头即可展开/收起。

---

## 构建与运行

需要用 **Android Studio** 打开本工程（本仓库不含 Android SDK 与 gradle-wrapper.jar，IDE 会自动补齐）。

要求：

- Android Studio Hedgehog / Iguana 或更新版本（自带 JDK 17）
- 设备 / 模拟器 API 21+（推荐 24+）
- 联网（首次构建需下载 Sora、`compiler-d8` 等依赖）

步骤：

1. `File → Open` 选择本目录（`JavaIDE/`）。
2. 等待 Gradle 同步完成。
3. 连接设备（或启动模拟器），点击 ▶ Run。

> 若导入时提示缺少 Gradle Wrapper，可在终端执行 `gradle wrapper`（本机已装 Gradle）后再用 Android Studio 打开，或直接让 Android Studio 重新生成。

---

## 目录结构

```
JavaIDE/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/javaide/
│       │   ├── App.kt                 # 初始化 JavaEngine（解压 rt.jar）
│       │   ├── MainActivity.kt        # 入口
│       │   ├── IDEViewModel.kt        # 工程状态 + 编译/运行管线 + 控制台
│       │   ├── FileNode.kt            # 文件树节点
│       │   ├── Snippets.kt            # 内置快捷提示词
│       │   ├── SnippetEngine.kt       # 词解析 / 片段插入逻辑
│       │   ├── FileUtils.kt           # 文件树构建 / 包名推导 / 示例工程
│       │   └── ui/
│       │       ├── IDEScreen.kt       # 整体布局 + 顶栏 + 对话框
│       │       ├── FileTreePanel.kt   # 左侧目录树
│       │       ├── ConsolePanel.kt    # 控制台（输出 + 标准输入）
│       │       ├── CodeEditorView.kt  # Sora 编辑器嵌入
│       │       ├── SnippetPopup.kt    # 片段候选弹窗
│       │       └── EditorActions.kt   # 插入片段
│       └── res/values/{strings.xml,themes.xml}
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── gradle/wrapper/gradle-wrapper.properties
```

---

## 使用说明

- **运行**：顶栏 ▶ 按钮。会自动保存当前文件、编译整个 `src`、转换 Dex 并运行首个含 `main` 的类。
- **标准输入**：程序运行且控制台展开时，底部出现输入框，输入后点“发送”（或键盘 Send），数据写入 `System.in`。
- **停止**：控制台右上“停止”按钮，关闭正在运行的程序。
- **片段提示**：在编辑器里输入触发词（如 `psvm`），上方出现候选，点击即可展开；继续输入会实时过滤。
- **撤销 / 重做**：顶栏 ↩ / ↪ 按钮，等价于桌面端 Ctrl+Z / Ctrl+Y；可用状态随编辑内容自动变化。
- **文件重命名 / 删除**：在左侧目录树中 **长按某个文件**，弹出菜单选择“重命名”或“删除”（删除目录会递归进行，均需二次确认）。
- **新建文件 / 包 / src / out**：顶栏 ⋮ 菜单。新建文件默认按所在目录推导 `package` 并生成类骨架。

---

## 主要依赖

| 依赖 | 用途 |
| --- | --- |
| `io.github.rosemoe:editor:0.24.4` + `language-java` | 代码编辑器与 Java 高亮/补全 |
| `io.github.xiaoyvyv:compiler-d8:1.0.4` | 安卓端 Java 编译 + Dex + 反射运行 |
| Jetpack Compose (BOM 2024.10.01) | UI |

---

## 注意事项

- 编译/运行依赖 `JavaEngine.init` 在首次启动时把自带的精简 `rt.jar` 解压到缓存；若一打开就立刻点运行，极少数情况可能赶上解压未完成，稍等片刻再点运行即可。
- `compiler-d8` 约 9MB（含精简 JRE 资源），首次构建耗时稍长。
- 本工程在沙箱环境中完成代码编写，未在真机/模拟器上实测；如构建或运行报错，优先检查依赖版本与 `JavaEngine` 的初始化时机。
