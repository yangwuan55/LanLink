package com.ymr.lancomm.domain.model

import com.ymr.lancomm.platform.nowMillis
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class LanMessage @OptIn(ExperimentalUuidApi::class) constructor(
    val id: String = Uuid.random().toString(),
    val timestamp: Long = nowMillis(),
    val payload: String
)