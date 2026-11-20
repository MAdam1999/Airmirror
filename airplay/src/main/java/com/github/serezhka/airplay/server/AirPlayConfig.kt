package com.github.serezhka.airplay.server

class AirPlayConfig {
    var serverName: String = ""
    var width: Int = 0
    var height: Int = 0
    var fps: Int = 0
    var isHlsEnabled: Boolean = false
    var isMdnsLoopback: Boolean = false
    /** System tray menu (cast picker + quit). Bound from `airplay.tray-enabled`. */
    var isTrayEnabled: Boolean = true

    /**
     * AirPlay password (PIN). When set, a phone must type it before it can mirror or play audio;
     * null or empty means no password. Read on every connection, so it can change while running.
     */
    @Volatile
    var password: String? = null
}
