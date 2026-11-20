package com.github.serezhka.airplay.server.internal

import com.github.serezhka.airplay.protocol.FairPlaySession
import com.github.serezhka.airplay.server.Playback
import com.github.serezhka.airplay.server.internal.decoder.AudioDecoder
import com.github.serezhka.airplay.server.internal.handler.audio.AudioHandler
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.handler.codec.DatagramPacketDecoder
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch

class AudioServer(private val airPlay: FairPlaySession) : Runnable {

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

    override fun run() {
        val workerGroup = NettyEventLoops.create()
        val consumer = airPlayConsumer
        try {
            val channelFuture = Bootstrap()
                .group(workerGroup)
                .channel(NioDatagramChannel::class.java) // Android: NIO only
                .localAddress(InetSocketAddress(0)) // bind random port
                .handler(object : ChannelInitializer<DatagramChannel>() {
                    override fun initChannel(ch: DatagramChannel) {
                        ch.pipeline().addLast("audioDecoder", DatagramPacketDecoder(AudioDecoder()))
                        ch.pipeline().addLast("audioHandler", AudioHandler(airPlay, consumer!!))
                    }
                })
                .bind().sync()

            port = (channelFuture.channel().localAddress() as InetSocketAddress).port
            log.info("AirPlay audio server listening on port: {}", port)
            started.countDown()

            channelFuture.channel().closeFuture().sync()
        } catch (e: InterruptedException) {
            log.debug("AirPlay audio server interrupted")
        } finally {
            log.debug("AirPlay audio server stopped")
            workerGroup.shutdownGracefully()
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AudioServer::class.java)
    }
}
