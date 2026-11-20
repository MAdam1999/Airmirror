package com.github.serezhka.airplay.server.internal.handler.session

import io.netty.channel.ChannelHandlerContext

class PlaylistRequest(
    val context: ChannelHandlerContext,
    val protocol: String,
    val method: String,
    val uri: String,
    val requestHeaders: Map<String, String>,
    val requestBody: ByteArray,
    val keepAlive: Boolean,
)
