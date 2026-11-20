package com.github.serezhka.airplay.server.discovery

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Registers airplay/airtunes service mdns
 */
class MdnsAdvertiser(
    private val serverName: String,
    private val hlsEnabled: Boolean,
    private val pairingPublicKey: String,
    private val mdnsLoopback: Boolean,
) {

    private val jmDNSList = ArrayList<JmDNS>()

    fun start(airTunesPort: Int) {
        log.info(
            "HLS {} features {} pk {}", if (hlsEnabled) "enabled" else "disabled",
            AdvertisedReceiver.featuresTxt(hlsEnabled), pairingPublicKey,
        )
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter(::acceptInterface)
            .flatMap { Collections.list(it.inetAddresses) }
            .filter { it is Inet4Address }
            .forEach { inetAddress -> register(inetAddress, airTunesPort) }
    }

    private fun register(inetAddress: InetAddress, airTunesPort: Int) {
        try {
            val hardwareAddress = NetworkInterface.getByInetAddress(inetAddress).hardwareAddress
            val mac = hardwareAddressBytesToString(hardwareAddress)

            val jmDNS = JmDNS.create(inetAddress)
            jmDNS.registerService(
                ServiceInfo.create(serverName + AIRPLAY_SERVICE_TYPE, serverName, airTunesPort, 0, 0, airPlayMDNSProps(mac)),
            )
            val airTunesServerName = mac.replace(":", "") + "@" + serverName
            jmDNS.registerService(
                ServiceInfo.create(
                    airTunesServerName + AIRTUNES_SERVICE_TYPE, airTunesServerName, airTunesPort, 0, 0, airTunesMDNSProps(),
                ),
            )
            // Loopback registrations are noise at INFO; LAN address is the discovery path.
            if (inetAddress.isLoopbackAddress) {
                log.debug(
                    "{} / {} registered on {}, port {}",
                    serverName + AIRPLAY_SERVICE_TYPE,
                    airTunesServerName + AIRTUNES_SERVICE_TYPE,
                    inetAddress.hostAddress, airTunesPort,
                )
            } else {
                log.info(
                    "{} registered on {}, port {}",
                    serverName + AIRPLAY_SERVICE_TYPE, inetAddress.hostAddress, airTunesPort,
                )
            }

            jmDNSList.add(jmDNS)
        } catch (e: IOException) {
            log.error(e.message)
        }
    }

    fun stop() {
        for (jmDNS in jmDNSList) {
            jmDNS.unregisterAllServices()
            try {
                jmDNS.close()
            } catch (e: IOException) {
                log.warn(e.message)
            }
        }
        jmDNSList.clear()
    }

    private fun airPlayMDNSProps(deviceId: String): Map<String, String> = hashMapOf(
        "deviceid" to deviceId,
        "features" to AdvertisedReceiver.featuresTxt(hlsEnabled),
        "srcvers" to AdvertisedReceiver.SOURCE_VERSION,
        "flags" to AdvertisedReceiver.MDNS_FLAGS,
        "vv" to AdvertisedReceiver.VV.toString(),
        "model" to AdvertisedReceiver.MODEL,
        "rhd" to "5.6.0.0",
        "pw" to "false",
        "pk" to pairingPublicKey,
    )

    private fun airTunesMDNSProps(): Map<String, String> = hashMapOf(
        "ch" to "2",
        "cn" to "1,3",
        "da" to "true",
        "et" to "0,3,5",
        "ek" to "1",
        "ft" to AdvertisedReceiver.featuresTxt(hlsEnabled),
        "am" to AdvertisedReceiver.MODEL,
        "md" to "0,1,2",
        "sr" to "44100",
        "ss" to "16",
        "sv" to "false",
        "sm" to "false",
        "tp" to "UDP",
        "txtvers" to "1",
        "sf" to AdvertisedReceiver.MDNS_FLAGS,
        "vs" to AdvertisedReceiver.SOURCE_VERSION,
        "vn" to "65537",
        "pk" to pairingPublicKey,
    )

    private fun acceptInterface(networkInterface: NetworkInterface): Boolean = try {
        when {
            !networkInterface.isUp || networkInterface.isPointToPoint -> false
            networkInterface.isLoopback -> mdnsLoopback
            else -> true
        }
    } catch (e: SocketException) {
        false
    }

    private fun hardwareAddressBytesToString(mac: ByteArray?): String {
        if (mac == null || mac.size != 6 || (deviceIdOverride != null && (mac[0].toInt() and 0x02) != 0)) {
            return fallbackMac() // loopback, or Android 11+ which hides the real MAC
        }
        return mac.joinToString(":") { String.format("%02X", it) }.uppercase()
    }

    private fun fallbackMac(): String = deviceIdOverride ?: "00:00:00:00:00:00"

    companion object {
        private val log = LoggerFactory.getLogger(MdnsAdvertiser::class.java)

        private const val AIRPLAY_SERVICE_TYPE = "._airplay._tcp.local"
        private const val AIRTUNES_SERVICE_TYPE = "._raop._tcp.local"

        /** Stable device id supplied by the host app when the OS hides the interface MAC. */
        @JvmStatic
        @Volatile
        var deviceIdOverride: String? = null
    }
}
