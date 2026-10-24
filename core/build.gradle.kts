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

    // 浏览器复用公共 DTO、BP/2 codec 和摘要；TLS socket 载体仍在 JVM 共享源集。
    wasmJs {
        browser()
        nodejs()
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
            api(libs.okio)
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

// Manual measurement, separate from pass/fail regression tests and their throughput floors.
tasks.register<JavaExec>("bp2Benchmark") {
    group = "verification"
    description = "Compare BP/2 with the standard LocalSend adapter on loopback using identical storage."
    dependsOn("jvmTestClasses")
    val compilation = kotlin.targets.getByName("jvm").compilations.getByName("test")
    classpath = files(compilation.output.allOutputs, configurations.getByName("jvmTestRuntimeClasspath"))
    mainClass.set("dev.boxlet.core.Bp2ProtocolBenchmark")
    args(layout.buildDirectory.file("reports/bp2-protocols.json").get().asFile.absolutePath)
}

// Keep the existing throughput gates, with each performance class in a fresh JVM so earlier
// TLS/session tests cannot change its warmup, heap or shared HTTP/coroutine pool state.
val throughputTest = tasks.register<Test>("throughputTest") {
    group = "verification"
    description = "Run the existing throughput floors in isolated JVMs."
    dependsOn("jvmTestClasses")
    val compilation = kotlin.targets.getByName("jvm").compilations.getByName("test")
    testClassesDirs = compilation.output.classesDirs
    classpath = files(compilation.output.allOutputs, configurations.getByName("jvmTestRuntimeClasspath"))
    include("**/*ThroughputTest.class")
    forkEvery = 1
    maxParallelForks = 1
}
tasks.named<Test>("jvmTest") {
    exclude("**/*ThroughputTest.class")
    dependsOn(throughputTest)
}
