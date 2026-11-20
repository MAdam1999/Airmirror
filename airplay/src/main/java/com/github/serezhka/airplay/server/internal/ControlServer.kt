package com.github.serezhka.airplay.server.internal

import com.github.serezhka.airplay.protocol.pairing.PairingIdentity
import com.github.serezhka.airplay.server.AirPlayConfig
import com.github.serezhka.airplay.server.Playback
import com.github.serezhka.airplay.server.internal.handler.control.ControlHandler
import com.github.serezhka.airplay.server.internal.handler.control.HlsFcupService
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.rtsp.RtspDecoder
import io.netty.handler.codec.rtsp.RtspEncoder
import io.netty.handler.logging.ByteBufFormat
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch

class ControlServer(
    private val airPlayConfig: AirPlayConfig,
    private val airPlayConsumer: Playback,
    identity: PairingIdentity,
) : Runnable {

    private val sessionManager = SessionManager(identity)
    private val hlsFcupService = HlsFcupService(sessionManager, airPlayConsumer)

    private var thread: Thread? = null
    private val started = CountDownLatch(1)

    @Volatile
    var port = 0
        private set

    init {
        airPlayConsumer.setObserver(object : Playback.Observer {
            override fun onEnded() = hlsFcupService.refreshActivePlaylists()
            override fun onPresented() = hlsFcupService.onPlaybackPresented()
            override fun onSeekDisplayed() = hlsFcupService.onSeekDisplayed()
        })
    }

    fun start() {
        thread = Thread(this).also { it.start() }
        started.await()
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    override fun run() {
        val bossGroup = NettyEventLoops.create()
        val workerGroup = NettyEventLoops.create()
        try {
            val channelFuture = ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java) // Android: NIO only
                .localAddress(InetSocketAddress(0))
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().addLast(
                            RtspDecoder(),
                            RtspEncoder(),
                            HttpObjectAggregator(2 * 1024 * 1024),
                            LoggingHandler(LogLevel.DEBUG, ByteBufFormat.SIMPLE),
                            ControlHandler(sessionManager, hlsFcupService, airPlayConfig, airPlayConsumer),
                        )
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .bind().sync()

            port = (channelFuture.channel().localAddress() as InetSocketAddress).port
            log.info("AirPlay control server listening on port: {}", port)
            started.countDown()

            channelFuture.channel().closeFuture().sync()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt() // stop() interrupts the server thread
        } finally {
            log.info("AirPlay control server stopped")
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ControlServer::class.java)
    }
}
