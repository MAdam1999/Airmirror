package com.github.serezhka.airplay.server.internal.handler.session

import com.github.serezhka.airplay.protocol.FairPlaySession
import com.github.serezhka.airplay.protocol.pairing.PairingIdentity
import com.github.serezhka.airplay.server.internal.AudioControlServer
import com.github.serezhka.airplay.server.internal.AudioServer
import com.github.serezhka.airplay.server.internal.VideoServer
import io.netty.channel.ChannelHandlerContext
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class Session internal constructor(val id: String, identity: PairingIdentity) {

    val airPlay = FairPlaySession(identity)
    val videoServer = VideoServer(airPlay)
    val audioServer = AudioServer(airPlay)
    val audioControlServer = AudioControlServer()
    val reverseContexts: MutableMap<String, ChannelHandlerContext> = ConcurrentHashMap()
    val playlistRequests: MutableMap<String, Queue<PlaylistRequest>> = ConcurrentHashMap()

    @Volatile
    var hlsPlaylistState: HlsPlaylistState? = null

    fun enqueuePlaylistRequest(remoteUri: String, request: PlaylistRequest) {
        playlistRequests.computeIfAbsent(remoteUri) { ConcurrentLinkedQueue() }.add(request)
    }

    fun pollPlaylistRequest(remoteUri: String): PlaylistRequest? = playlistRequests[remoteUri]?.poll()
}
