package com.airmirror.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.github.serezhka.airplay.protocol.pairing.PairingIdentity
import com.github.serezhka.airplay.server.AirPlayConfig
import com.github.serezhka.airplay.server.AirPlayServer
import com.github.serezhka.airplay.server.discovery.MdnsAdvertiser
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Hosts the AirPlay receiver independent of [MainActivity], so it keeps listening for a
 * connection after the app is closed. While idle it only runs a couple of listening sockets and
 * the mDNS responder — no decoders, no surface, no real CPU use. Decoding only starts once a
 * phone actually begins mirroring, same as before.
 *
 * When mirroring starts (or a never-before-seen device asks to connect), this brings
 * [MainActivity] to the front so there is a screen to show the picture on, and so an
 * unapproved device's prompt is visible.
 */
class MirrorService : Service(), MirrorPlayback.Listener, MirrorPlayback.ApprovalGate {

    interface UiCallback {
        fun onStatusChanged() // ask the Activity to re-read name()/addresses()/isReady() etc.

        fun onMirroringStarted()

        fun onMirroringStopped()

        fun onVideoSize(width: Int, height: Int)

        /** Show an Allow/Deny prompt for a never-before-approved device. */
        fun onApprovalNeeded(deviceName: String)
    }

    inner class LocalBinder : Binder() {
        fun service(): MirrorService = this@MirrorService
    }

    private val binder: IBinder = LocalBinder()
    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var playback: MirrorPlayback
    @Volatile private var server: AirPlayServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var ui: UiCallback? = null

    // One pending approval at a time; AirPlay only has one active sender anyway.
    private var pendingApprovalName: String? = null
    private var pendingApprovalDecision: ((Boolean) -> Unit)? = null

    private val prefs
        get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onCreate() {
        super.onCreate()
        playback = MirrorPlayback(this)
        applyConnectMode()
        MdnsAdvertiser.deviceIdOverride = deviceId()
        startForeground(1, buildNotification(getString(R.string.status_starting)))
        startReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopReceiver()
        playback.release()
        serverExecutor.shutdown()
    }

    // --- UI binding: only while MainActivity is actually resumed ---

    fun registerUi(callback: UiCallback) {
        ui = callback
    }

    fun unregisterUi(callback: UiCallback) {
        if (ui === callback) {
            ui = null
        }
    }

    /** The Activity calls this right after registering, in case a prompt was waiting on it. */
    fun checkPendingApproval() {
        val name = synchronized(this) { pendingApprovalName }
        val callback = ui
        if (name != null && callback != null) {
            callback.onApprovalNeeded(name)
        }
    }

    fun resolveApproval(allow: Boolean) {
        val decision: ((Boolean) -> Unit)?
        val name: String?
        synchronized(this) {
            decision = pendingApprovalDecision
            name = pendingApprovalName
            pendingApprovalDecision = null
            pendingApprovalName = null
        }
        if (decision == null) return
        if (allow && name != null) {
            val approved = HashSet(prefs.getStringSet("approved_devices", emptySet()).orEmpty())
            approved.add(name)
            prefs.edit().putStringSet("approved_devices", approved).apply()
        }
        decision(allow)
        updateNotification()
    }

    fun playback(): MirrorPlayback = playback

    // --- Connect mode (Settings) ---

    fun connectMode(): String {
        val mode = prefs.getString("connect_mode", MODE_APPROVAL) ?: MODE_APPROVAL
        // PIN mode isn't built yet; never leave the receiver running unguarded because of it.
        return if (mode == MODE_PIN) MODE_APPROVAL else mode
    }

    fun setConnectMode(mode: String) {
        prefs.edit().putString("connect_mode", mode).apply()
        applyConnectMode()
    }

    private fun applyConnectMode() {
        val gated = connectMode() != MODE_NONE
        playback.approvalGate = if (gated) this else null
    }

    // --- MirrorPlayback.ApprovalGate (called from Netty threads) ---

    override fun requestApproval(deviceName: String, decision: (Boolean) -> Unit) {
        val approved = prefs.getStringSet("approved_devices", emptySet()).orEmpty()
        if (deviceName in approved) {
            decision(true)
            return
        }
        synchronized(this) {
            pendingApprovalName = deviceName
            pendingApprovalDecision = decision
        }
        bringAppToFront()
        mainHandler.post {
            updateNotification()
            ui?.onApprovalNeeded(deviceName)
        }
    }

    // --- MirrorPlayback.Listener (called from Netty threads) ---

    override fun onMirroringStarted() {
        bringAppToFront()
        mainHandler.post {
            updateNotification()
            ui?.onMirroringStarted()
        }
    }

    override fun onMirroringStopped() {
        mainHandler.post {
            updateNotification()
            ui?.onMirroringStopped()
        }
    }

    override fun onVideoSize(width: Int, height: Int) {
        mainHandler.post { ui?.onVideoSize(width, height) }
    }

    private fun bringAppToFront() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        startActivity(intent)
    }

    // --- Receiver lifecycle (moved out of the Activity so it survives the app closing) ---

    private fun startReceiver() {
        val name = receiverName()
        val addresses = localAddresses()
        if (addresses.isEmpty()) {
            updateNotification()
            mainHandler.postDelayed({ startReceiver() }, 5000) // Wi-Fi may still be coming up
            return
        }

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        if (wifi != null && multicastLock == null) {
            multicastLock = wifi.createMulticastLock("airplay-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        val config = AirPlayConfig().apply {
            serverName = name
            width = 1920
            height = 1080
            fps = 60
            isHlsEnabled = true // also offer the AirPlay button inside Safari/YouTube/etc, not just mirroring
            isMdnsLoopback = false
            isTrayEnabled = false
        }

        serverExecutor.execute {
            try {
                val s = AirPlayServer(config, playback, pairingIdentity())
                s.start()
                server = s
                Log.i(TAG, "AirPlay receiver started as $name")
                mainHandler.post {
                    updateNotification()
                    ui?.onStatusChanged()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Cannot start AirPlay receiver", e)
            }
        }
    }

    private fun stopReceiver() {
        val s = server
        server = null
        if (s != null) {
            try {
                s.stop()
            } catch (e: Throwable) {
                Log.w(TAG, "Error stopping receiver", e)
            }
        }
        multicastLock?.let { if (it.isHeld) it.release() }
    }

    /** Restart under a new name without a full service restart, e.g. after Rename. */
    fun restart() {
        stopReceiver()
        startReceiver()
    }

    fun isReady(): Boolean = server != null

    fun name(): String = receiverName()

    fun addresses(): List<String> = localAddresses()

    // --- Notification ---

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
        nm?.notify(1, buildNotification(notificationText()))
    }

    private fun notificationText(): String {
        val pending = pendingApprovalName
        return when {
            pending != null -> getString(R.string.notif_approval, pending)
            server == null -> getString(R.string.status_starting)
            else -> getString(R.string.notif_waiting, receiverName())
        }
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW,
                )
                channel.setShowBadge(false)
                nm.createNotificationChannel(channel)
            }
        }
        val openIntent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(this, 0, openIntent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    // --- Identity / network (same as before, now owned by the service) ---

    private fun receiverName(): String {
        val custom = prefs.getString("name", null)
        if (!custom.isNullOrBlank()) {
            return cleanName(custom)
        }
        var name = Settings.Global.getString(contentResolver, "device_name")
        if (name.isNullOrBlank()) {
            name = Build.MODEL
        }
        return cleanName(name)
    }

    private fun deviceId(): String {
        prefs.getString("device_id", null)?.let { return it }
        val mac = ByteArray(6)
        SecureRandom().nextBytes(mac)
        mac[0] = ((mac[0].toInt() and 0xFC) or 0x02).toByte()
        val id = mac.joinToString(":") { String.format("%02X", it) }
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    /**
     * The receiver's cryptographic identity, kept stable across restarts. Without this, every
     * restart (every reinstall, rename, or Settings change) presented the phone with a brand-new
     * identity even though the device id/name stayed the same — which is exactly the mismatch
     * AirPlay's pairing treats as suspicious, silently refusing to (re)connect afterward.
     */
    private fun pairingIdentity(): PairingIdentity {
        val hex = prefs.getString("pairing_seed", null)
        val seed: ByteArray
        if (hex == null) {
            seed = PairingIdentity.newSeed()
            val encoded = seed.joinToString("") { String.format("%02x", it) }
            prefs.edit().putString("pairing_seed", encoded).apply()
        } else {
            seed = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
        return PairingIdentity.fromSeed(seed)
    }

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "airplay_receiver"
        private const val PREFS = "receiver"

        /** How a new connection is let through. Kept as plain strings so they're simple prefs values. */
        const val MODE_APPROVAL = "approval" // ask Allow/Deny on the TV (default)
        const val MODE_PIN = "pin" // enter a code on the iPhone — not implemented yet
        const val MODE_NONE = "none" // anyone can connect automatically

        private fun cleanName(raw: String): String = raw.replace('.', ' ').trim()

        private fun localAddresses(): List<String> {
            val result = ArrayList<String>()
            try {
                for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp || ni.isLoopback || ni.isPointToPoint) continue
                    for (address in Collections.list(ni.inetAddresses)) {
                        if (address is Inet4Address) {
                            address.hostAddress?.let { result.add(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot list network interfaces", e)
            }
            return result
        }
    }
}
