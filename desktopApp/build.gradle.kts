import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":app-shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(libs.fluent)
    implementation(libs.fluent.icons.extended)
    implementation(libs.kotlinx.coroutines.swing)
    // 调用 Windows DWM 实现原生 Mica 与沉浸式深色标题栏
    implementation("net.java.dev.jna:jna:5.6.0")
    implementation("net.java.dev.jna:jna-platform:5.6.0")
}

compose.desktop {
    application {
        mainClass = "dev.boxlet.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Boxlet"
            packageVersion = "1.0.0"
            description = "Boxlet - local network file transfer"
            vendor = "Boxlet"
            windows {
                iconFile.set(project.file("../design/brand/boxlet.ico"))
            }
        }
    }
}
