package com.github.serezhka.airplay.server.internal

import com.github.serezhka.airplay.server.internal.handler.audio.AudioControlHandler
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.nio.NioDatagramChannel
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch

class AudioControlServer : Runnable {

    private var thread: Thread? = null
    private var started = CountDownLatch(1)

    @Volatile
    var port = 0
        private set

    fun start() {
        port = 0
        started = CountDownLatch(1)
        thread = Thread(this).also { it.start() }
        started.await()
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    override fun run() {
        val workerGroup = NettyEventLoops.create()
        try {
            val channelFuture = Bootstrap()
                .group(workerGroup)
                .channel(NioDatagramChannel::class.java) // Android: NIO only
                .localAddress(InetSocketAddress(0)) // bind random port
                .handler(object : ChannelInitializer<DatagramChannel>() {
                    override fun initChannel(ch: DatagramChannel) {
                        ch.pipeline().addLast("audioControlHandler", AudioControlHandler())
                    }
                })
                .bind().sync()

            port = (channelFuture.channel().localAddress() as InetSocketAddress).port
            log.info("AirPlay audio control server listening on port: {}", port)
            started.countDown()

            channelFuture.channel().closeFuture().sync()
        } catch (e: InterruptedException) {
            log.debug("AirPlay audio control server interrupted")
        } finally {
            log.debug("AirPlay audio control server stopped")
            workerGroup.shutdownGracefully()
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AudioControlServer::class.java)
    }
}
