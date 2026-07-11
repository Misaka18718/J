package com.example.javaide

import android.app.Application
import com.xiaoyv.java.compiler.JavaEngine

/**
 * 应用入口。
 * 必须在 [JavaEngine.init] 中初始化编译/运行引擎：
 * 它会把自带的精简 rt.jar 从 assets 解压到缓存目录，
 * 后续编译 Java 源码、转换为 Dex、运行 main 方法都依赖它。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        JavaEngine.init(this)
    }
}
