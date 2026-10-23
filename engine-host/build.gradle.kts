import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
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
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)
}

application {
    mainClass.set("dev.boxlet.engine.MainKt")
}

// 版本号来自 git tag（根工程统一解析），打到 MANIFEST 里，便于外部读取。
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Boxlet Engine",
            "Implementation-Version" to project.version,
        )
    }
}
