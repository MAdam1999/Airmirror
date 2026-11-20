package com.github.serezhka.airplay.server.internal

import com.github.serezhka.airplay.protocol.FairPlaySession
import com.github.serezhka.airplay.server.Playback
import com.github.serezhka.airplay.server.internal.decoder.VideoDecoder
import com.github.serezhka.airplay.server.internal.handler.video.VideoHandler
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch

class VideoServer(private val airPlay: FairPlaySession) : Runnable {

    private var thread: Thread? = null
    private var airPlayConsumer: Playback? = null
    private var started = CountDownLatch(1)

    @Volatile
    var port = 0
        private set

    fun start(airPlayConsumer: Playback) {
        this.airPlayConsumer = airPlayConsumer
        port = 0
        started = CountDownLatch(1)
        thread = Thread(this).also { it.start() }
        started.await()
    }

    fun stop() {
        thread?.let {
            it.interrupt()
            thread = null
            airPlayConsumer = null
        }
    }

    /** True while the accept thread is alive (mirror video path). */
    val isRunning: Boolean
        get() = thread?.isAlive == true

    override fun run() {
        val bossGroup = NettyEventLoops.create()
        val workerGroup = NettyEventLoops.create()
        val consumer = airPlayConsumer
        try {
            val channelFuture = ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java) // Android: NIO only
                .localAddress(InetSocketAddress(0)) // bind random port
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().addLast("videoDecoder", VideoDecoder())
                        ch.pipeline().addLast("videoHandler", VideoHandler(airPlay, consumer!!))
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .bind().sync()

            port = (channelFuture.channel().localAddress() as InetSocketAddress).port
            log.info("AirPlay video server listening on port: {}", port)
            started.countDown()

            channelFuture.channel().closeFuture().sync()
        } catch (e: InterruptedException) {
            log.debug("AirPlay video server interrupted")
        } finally {
            log.debug("AirPlay video server stopped")
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(VideoServer::class.java)
    }
}
