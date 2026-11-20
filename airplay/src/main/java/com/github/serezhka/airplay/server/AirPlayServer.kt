package com.github.serezhka.airplay.server

import com.github.serezhka.airplay.protocol.pairing.PairingIdentity
import com.github.serezhka.airplay.server.discovery.MdnsAdvertiser
import com.github.serezhka.airplay.server.internal.ControlServer

/**
 * Pass the same [PairingIdentity] every time (see [PairingIdentity.fromSeed]) so a
 * phone that already paired with this receiver keeps recognising it after a restart.
 */
class AirPlayServer @JvmOverloads constructor(
    airPlayConfig: AirPlayConfig,
    airPlayConsumer: Playback,
    identity: PairingIdentity = PairingIdentity.generate(),
) {

    private val airPlayBonjour = MdnsAdvertiser(
        airPlayConfig.serverName,
        airPlayConfig.isHlsEnabled,
        identity.publicKeyHex(),
        airPlayConfig.isMdnsLoopback,
    )
    private val controlServer = ControlServer(airPlayConfig, airPlayConsumer, identity)

    fun start() {
        controlServer.start()
        airPlayBonjour.start(controlServer.port)
    }

    fun stop() {
        airPlayBonjour.stop()
        controlServer.stop()
    }
}
