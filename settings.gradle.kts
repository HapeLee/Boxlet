pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Kotlin/Wasm 工具链会在项目内注册 Node.js / Yarn 的分发仓库，
    // FAIL_ON_PROJECT_REPOS 会让 `:kotlinWasmNodeJsSetup` 直接失败。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Kotlin/Wasm 的 Node.js 工具链从这里取，不在 Maven 上。
        ivy("https://nodejs.org/dist") {
            name = "Node Distributions"
            patternLayout { artifact("v[revision]/[artifact]-v[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn Distributions"
            patternLayout { artifact("v[revision]/[artifact]-v[revision].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen Distributions"
            patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "Boxlet"

include(":core")
include(":app-shared")
include(":androidApp")
include(":desktopApp")
include(":engine-host")
include(":webApp")
