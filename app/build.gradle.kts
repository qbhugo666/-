import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式签名（keystore.properties 不入库，密钥与密码只留在本机 + HANDOFF 备份说明）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.voicecontrol.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voicecontrol.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 87
        versionName = "0.55.9"

        // 只打包真机需要的两种 ARM 架构，减小 APK 体积
        // （arm64-v8a = 现代手机；armeabi-v7a = 老旧 32 位手机）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 密钥文件缺失时不签名（构建出的包无法安装，等于显式报错），绝不静默回退 debug 签名
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 大模型文件放在 assets，禁止压缩（模型本身已是压缩格式，再压会拖慢加载）
    androidResources {
        noCompress += listOf("onnx", "txt", "fst")
    }
}

dependencies {
    // 离线语音识别引擎：Sherpa-ONNX（含 ONNX 运行时 + JNI 原生库）
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))

    // 汉字转拼音：命令纠错时把「滑动/华动」这类同音字统一成拼音再比对
    implementation("com.belerweb:pinyin4j:2.5.0")

    // 单元测试：匹配逻辑回归测试（阶段一：测试基建）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
