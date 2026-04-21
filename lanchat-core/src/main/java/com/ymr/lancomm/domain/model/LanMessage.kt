package com.ymr.lancomm.domain.model

import java.util.UUID

data class LanMessage(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val payload: String
)