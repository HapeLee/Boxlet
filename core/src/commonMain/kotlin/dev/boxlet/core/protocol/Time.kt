package dev.boxlet.core.protocol

import kotlin.time.Instant

/**
 * 当前时间（毫秒）。协议层要跨 JVM 与 wasm 复用，所以不能直接用 `System.currentTimeMillis()`。
 */
internal expect fun nowMillis(): Long

/**
 * RFC 3339 → epoch millis。对端字段不可信，解析失败返回 null 而不是抛异常。
 * 用于接收侧还原 `metadata.modified`。
 */
fun rfc3339ToMillis(value: String?): Long? = value
    ?.takeIf { it.isNotBlank() }
    ?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
