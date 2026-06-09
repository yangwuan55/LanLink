package com.ymr.lanlink.core.domain.model

import com.ymr.lanlink.core.platform.nowMillis
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class LanMessage @OptIn(ExperimentalUuidApi::class) constructor(
    val id: String = Uuid.random().toString(),
    val timestamp: Long = nowMillis(),
    val payload: String
)