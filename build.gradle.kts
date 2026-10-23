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
// 唯一来源是 git tag：vMAJOR.MINOR.PATCH[.TWEAK]。数值用十进制拼接，每段固定 2 位：
//
//   code = major * 1_000_000 + minor * 10_000 + patch * 100 + tweak
//
// 例：v1.2.3 -> 1020300，v1.2.3.4 -> 1020304，v1.0.0 -> 1000000。
// 作用与 Android 的 versionCode 一致：随版本单调递增，段位一眼能对上版本名。
// 每段上限 99，最大值 99999999，远低于 Android 的 2100000000。
// tag 之后的提交与本地脏工作区只改变展示用的后缀（-dev.N+gHASH[.dirty]），
// 不改变数值版本号；正式发布必须打在干净的 tag 上。
//
// CI 用 BOXLET_VERSION 环境变量（或 -PboxletVersion=）覆盖，这样打 tag 的流水线
// 得到的就是 tag 对应的版本；本地构建没有该变量时回退到 git describe。
// ---------------------------------------------------------------------------

class BoxletVersionInfo(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val tweak: Int,
    val distance: Int,
    val commit: String,
    val dirty: Boolean,
    val tagged: Boolean,
) {
    /** 纯数值版本：1.2.3，带 tweak 时为 1.2.3.4。 */
    val semver: String = if (tweak == 0) "$major.$minor.$patch" else "$major.$minor.$patch.$tweak"

    /** 精确构建在干净 tag 上。 */
    val release: Boolean = tagged && distance == 0 && !dirty

    /** 十进制拼接的数值版本号，Android versionCode 直接用它。 */
    val code: Int = major * 1_000_000 + minor * 10_000 + patch * 100 + tweak

    /** 对内展示的完整版本号：发布版 1.2.3，开发构建 1.2.3-dev.7+ga1b2c3d。 */
    val display: String = when {
        release -> semver
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

fun resolveBoxletVersion(): BoxletVersionInfo {
    val pattern = Regex(
        """^v?(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?:\.(\d{1,3}))?(?:-(\d+)-g([0-9a-fA-F]+))?(-dirty)?$""",
    )
    val requested = (providers.gradleProperty("boxletVersion").orNull
        ?: providers.environmentVariable("BOXLET_VERSION").orNull).nonBlank()

    fun parse(text: String, fallbackCommit: String?): BoxletVersionInfo {
        val match = pattern.matchEntire(text)
            ?: throw GradleException("版本号格式非法：$text（期望 v1.2.3 或 v1.2.3.4）")
        return BoxletVersionInfo(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].toInt(),
            patch = match.groupValues[3].toInt(),
            tweak = match.groupValues[4].ifEmpty { "0" }.toInt(),
            distance = match.groupValues[5].ifEmpty { "0" }.toInt(),
            commit = match.groupValues[6].ifEmpty { fallbackCommit ?: "unknown" },
            dirty = match.groupValues[7].isNotEmpty(),
            tagged = true,
        )
    }

    val info = if (requested != null) {
        parse(requested, boxletGit("rev-parse", "--short=7", "HEAD"))
    } else {
        val described = boxletGit("describe", "--tags", "--match", "v[0-9]*", "--dirty")
        if (described != null) {
            parse(described, null)
        } else {
            // 还没有任何 v* tag：按 0.1.0 作为开发基线（Android versionCode 不能为 0）。
            BoxletVersionInfo(
                major = 0,
                minor = 1,
                patch = 0,
                tweak = 0,
                distance = 0,
                commit = boxletGit("rev-parse", "--short=7", "HEAD") ?: "unknown",
                dirty = false,
                tagged = false,
            )
        }
    }

    // 十进制拼接每段固定 2 位，因此每段最多 99；最大值 99999999 仍在 Android 的上限之内。
    listOf(
        "major" to info.major,
        "minor" to info.minor,
        "patch" to info.patch,
        "tweak" to info.tweak,
    ).forEach { (name, value) ->
        require(value in 0..99) {
            "$name 必须 ≤ 99（当前 $value）：versionCode 按每段 2 位十进制拼接，${info.semver} → ${info.code}"
        }
    }
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
                appendLine("// 由 :generateBoxletVersionSource 从 git tag 生成，请勿手改。")
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
