plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// ---------------------------------------------------------------------------
// 统一版本号
//
// 唯一来源是根目录 version.properties（三段，不使用第 4 段）。数值用十进制拼接，
// 每段固定 2 位：
//
//   code = major * 10_000 + minor * 100 + patch
//
// 例：v0.1.0 -> 100，v1.0.0 -> 10000，v1.2.3 -> 10203。
// 作用与 Android 的 versionCode 一致：随版本单调递增，段位一眼能对上版本名。
// 每段上限 99，最大值 999999，远低于 Android 的 2100000000。
// 同一版本内的修订直接递增 patch（1.2.99 -> 1.3.0 依然单调）。
// VERSION_SUFFIX=1 时本地显示 -beta.local，CI 根据已有 tag 分配 -beta.N。
// 本地开发后缀不改变数值版本号；tag 用于记录发布与标识开发构建。
//
// CI 用 BOXLET_VERSION 环境变量（或 -PboxletVersion=）覆盖，这样打 tag 的流水线
// 四端使用同一个发布版本；本地构建没有覆盖值时读取 version.properties。
// ---------------------------------------------------------------------------

class BoxletVersionInfo(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val distance: Int,
    val commit: String,
    val dirty: Boolean,
    val tagged: Boolean,
    val prerelease: String = "",
) {
    /** 纯数值版本：1.2.3。 */
    val semver: String = "$major.$minor.$patch"

    /** 精确构建在干净 tag 上。 */
    val release: Boolean = tagged && distance == 0 && !dirty && prerelease.isEmpty()

    /** 十进制拼接的数值版本号，Android versionCode 直接用它。 */
    val code: Int = major * 10_000 + minor * 100 + patch

    /** 对内展示的完整版本号：发布版 1.2.3，开发构建 1.2.3-dev.7+ga1b2c3d。 */
    val display: String = when {
        prerelease.isNotEmpty() -> "$semver-$prerelease"
        tagged && distance == 0 && !dirty -> semver
        distance > 0 -> "$semver-dev.$distance+g$commit" + if (dirty) ".dirty" else ""
        else -> "$semver+g$commit" + if (dirty) ".dirty" else ""
    }
}

fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

fun boxletGit(vararg args: String): String? = try {
    val execution = providers.exec {
        workingDir = rootDir
        commandLine(listOf("git", *args))
        isIgnoreExitValue = true
    }
    if (execution.result.get().exitValue == 0) execution.standardOutput.asText.get().nonBlank() else null
} catch (error: Exception) {
    null
}

// CI 与本地共用此解析器，避免 workflow 与各端分别维护版本规则。
val boxletVersionProperties = java.util.Properties().apply {
    val source = rootProject.file("version.properties")
    require(source.isFile) { "缺少根目录 version.properties" }
    source.reader(Charsets.UTF_8).use { load(it) }
}
fun boxletVersionPart(key: String): Int {
    val value = boxletVersionProperties.getProperty(key)?.trim().orEmpty()
    require(Regex("""(?:0|[1-9][0-9]?)""").matches(value)) {
        "version.properties 中 $key 必须是 0–99 的整数（不带前导零），当前 '$value'"
    }
    return value.toInt()
}
val boxletMajor = boxletVersionPart("VERSION_MAJOR")
val boxletMinor = boxletVersionPart("VERSION_MINOR")
val boxletPatch = boxletVersionPart("VERSION_PATCH")
val boxletSuffix = boxletVersionProperties.getProperty("VERSION_SUFFIX")?.trim()
require(boxletSuffix in listOf("0", "1")) { "version.properties 中 VERSION_SUFFIX 必须是 0 或 1" }
val boxletBaseVersion = "$boxletMajor.$boxletMinor.$boxletPatch"

fun resolveBoxletVersion(): BoxletVersionInfo {
    val pattern = Regex("""^v?(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)(?:-(beta\.(?:[1-9][0-9]*|local)))?$""")
    val requested = (providers.gradleProperty("boxletVersion").orNull
        ?: providers.environmentVariable("BOXLET_VERSION").orNull).nonBlank()

    val commit = boxletGit("rev-parse", "--short=7", "HEAD") ?: "unknown"
    val info = if (requested != null) {
        val text = requested
        val match = pattern.matchEntire(text)
            ?: throw GradleException("版本号格式非法：$text（期望 主.次.修订 或 主.次.修订-beta.N，每段 ≤ 99）")
        BoxletVersionInfo(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].toInt(),
            patch = match.groupValues[3].toInt(),
            distance = 0,
            commit = commit,
            dirty = false,
            tagged = true,
            prerelease = match.groupValues[4],
        )
    } else {
        val tag = "v$boxletBaseVersion"
        BoxletVersionInfo(
            major = boxletMajor,
            minor = boxletMinor,
            patch = boxletPatch,
            distance = (boxletGit("rev-list", "--count", "$tag..HEAD")
                ?: boxletGit("rev-list", "--count", "HEAD"))?.toIntOrNull() ?: 0,
            commit = commit,
            dirty = boxletGit("status", "--porcelain") != null,
            tagged = boxletGit("tag", "--points-at", "HEAD")?.lines()?.contains(tag) == true,
            prerelease = if (boxletSuffix == "1") "beta.local" else "",
        )
    }

    // 十进制拼接每段固定 2 位，因此每段最多 99；最大值 999999 远在 Android 的上限之内。
    listOf(
        "major" to info.major,
        "minor" to info.minor,
        "patch" to info.patch,
    ).forEach { (name, value) ->
        require(value in 0..99) {
            "$name 必须 ≤ 99（当前 $value）：versionCode 按每段 2 位十进制拼接，${info.semver} → ${info.code}"
        }
    }
    require(info.code > 0) { "版本号不能为 0.0.0：Android versionCode 必须大于 0" }
    return info
}

val boxletVersion = resolveBoxletVersion()

allprojects {
    version = boxletVersion.display
}

extra["boxletVersionName"] = boxletVersion.display
extra["boxletVersionSemver"] = boxletVersion.semver
extra["boxletVersionCode"] = boxletVersion.code
extra["boxletVersionRelease"] = boxletVersion.release
extra["boxletGitCommit"] = boxletVersion.commit

// 生成跨平台共用的版本常量：Android / Compose 桌面 / engine-host / webApp 都读它。
val boxletGeneratedKotlin = layout.buildDirectory.dir("generated/boxlet/kotlin")

val generateBoxletVersionSource = tasks.register("generateBoxletVersionSource") {
    group = "build"
    description = "生成 dev.boxlet.core.BoxletBuild 版本常量"
    outputs.dir(boxletGeneratedKotlin)
    inputs.property("version", boxletVersion.display)
    inputs.property("semver", boxletVersion.semver)
    inputs.property("code", boxletVersion.code)
    inputs.property("commit", boxletVersion.commit)
    inputs.property("release", boxletVersion.release)
    doLast {
        val file = boxletGeneratedKotlin.get().file("dev/boxlet/core/BoxletBuild.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("// 由 :generateBoxletVersionSource 从 version.properties 生成，请勿手改。")
                appendLine("package dev.boxlet.core")
                appendLine()
                appendLine("object BoxletBuild {")
                appendLine("    const val NAME: String = \"Boxlet\"")
                appendLine("    const val VERSION: String = \"${boxletVersion.display}\"")
                appendLine("    const val VERSION_SEMVER: String = \"${boxletVersion.semver}\"")
                appendLine("    const val VERSION_CODE: Int = ${boxletVersion.code}")
                appendLine("    const val GIT_COMMIT: String = \"${boxletVersion.commit}\"")
                appendLine("    const val IS_RELEASE: Boolean = ${boxletVersion.release}")
                appendLine("}")
            },
        )
    }
}

tasks.register("printBoxletVersion") {
    group = "help"
    description = "打印统一版本号（CI 与打包脚本读取）"
    outputs.upToDateWhen { false }
    doLast {
        val lines = listOf(
            "BOXLET_VERSION=${boxletVersion.display}",
            "BOXLET_VERSION_SEMVER=${boxletVersion.semver}",
            "BOXLET_VERSION_CODE=${boxletVersion.code}",
            "BOXLET_GIT_COMMIT=${boxletVersion.commit}",
            "BOXLET_IS_RELEASE=${boxletVersion.release}",
        )
        println(lines.joinToString("\n"))
        if (boxletVersion.release) {
            logger.lifecycle("Boxlet 版本 ${boxletVersion.display}（versionCode ${boxletVersion.code}）")
        } else {
            logger.lifecycle("Boxlet 开发版本 ${boxletVersion.display}（versionCode ${boxletVersion.code}，非发布）")
        }
    }
}

extra["boxletVersionTask"] = generateBoxletVersionSource

// Release prepare 调用；只计算和校验，不创建 tag 或发布。
tasks.register("printBoxletReleaseVersion") {
    group = "help"
    description = "从 version.properties 计算发布版本，校验 tag，并输出给 CI"
    doLast {
        val pushedTag = providers.environmentVariable("BOXLET_RELEASE_TAG").orNull.nonBlank()
        val betaPattern = Regex("""^v${Regex.escape(boxletBaseVersion)}-beta\.([1-9][0-9]*)$""")
        val version = if (pushedTag != null) {
            require(if (boxletSuffix == "0") pushedTag == "v$boxletBaseVersion" else betaPattern.matches(pushedTag)) {
                "tag '$pushedTag' 与 version.properties 不一致（版本 $boxletBaseVersion，VERSION_SUFFIX=$boxletSuffix）"
            }
            pushedTag.removePrefix("v")
        } else {
            val candidate = if (boxletSuffix == "0") boxletBaseVersion else {
                val last = boxletGit("tag", "--list", "v$boxletBaseVersion-beta.*")
                    ?.lineSequence()
                    ?.mapNotNull { betaPattern.matchEntire(it)?.groupValues?.get(1) }
                    ?.map { it.toInt() }
                    ?.maxOrNull() ?: 0
                "$boxletBaseVersion-beta.${Math.addExact(last, 1)}"
            }
            require(boxletGit("rev-parse", "--verify", "refs/tags/v$candidate") == null) {
                "tag v$candidate 已存在，请先修改 version.properties 升版本；失败的 tag 流水线请直接重跑"
            }
            candidate
        }
        println("BOXLET_RELEASE_VERSION=$version")
        println("BOXLET_RELEASE_TAG=v$version")
        println("BOXLET_PRERELEASE=${boxletSuffix == "1"}")
    }
}
