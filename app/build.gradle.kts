plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.javaide"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.javaide"
        // compiler-d8（ECJ + D8 安卓端 Java 编译引擎）的 manifest 要求 minSdk >= 24
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // compiler-d8 (com.xiaoyv.java.compiler) 用更新的 Kotlin 编译，其元数据版本
        // 高于本编译器，跳过版本检查以避免 K2 在检查该不兼容类时崩溃。
        freeCompilerArgs += listOf(
            "-Xskip-metadata-version-check",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // ---- Compose ----
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // 以下由 Compose BOM 统一版本管理，避免与 Compose 1.7 不匹配
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---- 代码编辑器（含 Java 语法高亮与自动补全）----
    val sora = "0.24.4"
    implementation(platform("io.github.rosemoe:editor-bom:$sora"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-java")

    // ---- 安卓端 Java 编译 / Dex / 运行引擎 ----
    implementation("io.github.xiaoyvyv:compiler-d8:1.0.4")
}
