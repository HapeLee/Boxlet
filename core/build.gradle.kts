import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    androidLibrary {
        namespace = "dev.boxlet.core"
        compileSdk = 37
        minSdk = 24

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // 浏览器目标只需要 commonMain 里的协议 DTO，传输层仍是 JVM 专属。
    wasmJs {
        browser()
    }

    // Android 与 JVM 共享同一套 JVM API，业务代码集中在此源集。
    val jvmSharedMain = sourceSets.create("jvmSharedMain")
    jvmSharedMain.dependsOn(sourceSets.commonMain.get())
    sourceSets.getByName("androidMain").dependsOn(jvmSharedMain)
    sourceSets.getByName("jvmMain").dependsOn(jvmSharedMain)

    jvmSharedMain.dependencies {
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.serialization.json)
        api(libs.okio)
        implementation(libs.okhttp)
        implementation(libs.bouncycastle.prov)
        implementation(libs.bouncycastle.pkix)
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// 统一版本号：根工程按 git tag 生成 dev.boxlet.core.BoxletBuild，各端共用同一份常量。
kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(
    rootProject.tasks.named("generateBoxletVersionSource"),
)
