package dev.boxlet.core.protocol

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal actual fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
