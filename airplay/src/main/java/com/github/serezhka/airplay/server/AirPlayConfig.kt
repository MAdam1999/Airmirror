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
}
