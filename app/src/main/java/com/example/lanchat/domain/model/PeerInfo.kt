package com.example.lanchat.domain.model

import java.net.InetAddress

data class PeerInfo(
    val name: String,
    val host: InetAddress,
    val port: Int
)