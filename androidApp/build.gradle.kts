import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
}

// 统一版本号：根工程按 version.properties 解析，versionCode = major * 10000 + minor * 100 + patch。
val boxletVersionName = rootProject.extra["boxletVersionName"] as String
val boxletVersionCode = rootProject.extra["boxletVersionCode"] as Int

android {
    namespace = "dev.boxlet.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.boxlet.android"
        // Android 12 起才具备完整的边到边、Monet 取色与前台服务类型语义，
        // 界面（液态玻璃 / 动态取色）也按 31+ 设计，不再为低版本降级。
        minSdk = 31
        targetSdk = 37
        versionCode = boxletVersionCode
        versionName = boxletVersionName
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        // 正式签名：本地读仓库根目录 keystore.properties（不要提交），CI 用环境变量注入
        // BOXLET_KEYSTORE_PATH / BOXLET_KEYSTORE_PASSWORD / BOXLET_KEY_ALIAS / BOXLET_KEY_PASSWORD。
        // 两者都没有时 release 只产出未签名 APK。
        val keystoreProperties = rootProject.file("keystore.properties")
        val properties = if (keystoreProperties.exists()) {
            Properties().apply { keystoreProperties.inputStream().use { load(it) } }
        } else {
            null
        }

        fun credential(property: String, environment: String): String? =
            properties?.getProperty(property) ?: System.getenv(environment)

        val storePath = credential("storeFile", "BOXLET_KEYSTORE_PATH")
        if (storePath != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = credential("storePassword", "BOXLET_KEYSTORE_PASSWORD")
                keyAlias = credential("keyAlias", "BOXLET_KEY_ALIAS")
                keyPassword = credential("keyPassword", "BOXLET_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 与 release 并存安装：应用 id 带 .debug 后缀，图标换成 debug 黄（见 src/debug/res）
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 混淆 + 资源压缩。mapping.txt 固定落在
            // androidApp/build/outputs/mapping/release/mapping.txt，发布时务必归档用于反混淆。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md,LICENSE,NOTICE.md,NOTICE,DEPENDENCIES,README.md}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    implementation(project(":app-shared"))
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.backdrop)
    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
