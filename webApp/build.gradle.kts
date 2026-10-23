plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "boxlet.js"
            }
        }
        binaries.executable()
    }

    // Kotlin/Wasm 的 webpack 只生成 JS 入口，不会把 resources/index.html 放到产物目录。
    // 这里在打包结束后复制页面及 favicon，产物目录才是可以直接托管的静态站点。
    listOf(
        "wasmJsBrowserDevelopmentWebpack" to "kotlin-webpack/wasmJs/developmentExecutable",
        "wasmJsBrowserProductionWebpack" to "kotlin-webpack/wasmJs/productionExecutable",
    ).forEach { (taskName, outputPath) ->
        tasks.matching { it.name == taskName }.configureEach {
            doLast {
                copy {
                    from(layout.projectDirectory.dir("src/wasmJsMain/resources"))
                    into(layout.buildDirectory.dir(outputPath))
                }
            }
        }
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
