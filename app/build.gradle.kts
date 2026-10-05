plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.adcalm.guard"
    compileSdk = 35

    // 自适应：只有在项目路径含非 ASCII 字符时，才把构建产物重定向到纯 ASCII 路径。
    //
    // 原因：JVM 的类加载器加载不了含中文字符的类路径条目，单元测试 worker 会直接
    // 报 ClassNotFoundException；Windows 上还有一批 native 工具（aapt2 等）对
    // 非 ASCII 路径处理不干净。源码路径含中文则无影响。
    //
    // 刻意写成运行时检测而不是写死路径——写死会让别人克隆下来编译不了，
    // 甚至在别人的盘上凭空建目录。正常路径的项目不受影响，走标准的 build/。
    if (!rootDir.absolutePath.all { it.code < 128 }) {
        layout.buildDirectory.set(File(rootDir.parentFile, "adcalm-build/app"))
    }

    defaultConfig {
        applicationId = "cn.adcalm.guard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // ML Kit 的 OCR native 库每个 ABI 都接近 10MB。x86 / x86_64 只有模拟器
            // 用得上，真机清一色是 ARM。
            //
            // **只留 arm64-v8a**：2026-10-05 把 32 位的 armeabi-v7a 也去掉了，再省约 6.5MB。
            // 这是个自用侧载工具，目标设备就是 arm64 手机；真要装到 32 位设备上时，
            // 把 "armeabi-v7a" 加回这个列表即可。
            abiFilters += "arm64-v8a"

            // 模拟器是 x86_64 的，上面那两个 ABI 的包在模拟器上装不上。
            // 需要跑仪器测试时加 -PwithEmulatorAbi 构建：
            //   ./build.sh assembleDebug -PwithEmulatorAbi
            // 这样日常给真机用的包体积不受影响。
            if (project.hasProperty("withEmulatorAbi")) {
                abiFilters += "x86_64"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // L3 兜底：离线中文文字识别。用 bundled 版本而不是 unbundled——
    // 模型直接打进 APK，装好就能用，不需要经过 Google Play 服务下载（侧载场景下那条路走不通）。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // Shizuku：借 shell 权限的通道。
    // 有了它，强停其他应用就是一行 `am force-stop`，不用再去设置页点「强行停止」；
    // 而且完全不经过 AccessibilityService，应用检测不到，也不受 Android 17
    // Advanced Protection Mode 对无障碍 API 的限制。没装 Shizuku 时自动降级回无障碍路径。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    // Android 的 org.json 是框架的一部分，JVM 单测里不存在，需要真实的实现顶上
    testImplementation("org.json:json:20240303")

    // 仪器测试：验证那些纯逻辑测不到的集成层——资源打包、ML Kit 模型加载、
    // SharedPreferences 读写、文件 IO。这些在真机/模拟器上跑。
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
