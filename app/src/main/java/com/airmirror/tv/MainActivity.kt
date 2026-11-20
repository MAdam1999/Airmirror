package com.airmirror.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Full-screen AirPlay receiver UI. The actual receiver runs in [MirrorService], so it
 * keeps listening after this screen is closed; this Activity binds to it for the picture, the
 * status display and the approval prompt, and the service brings it back to the front itself
 * whenever a phone actually starts mirroring.
 */
class MainActivity : Activity(), MirrorService.UiCallback, TextureView.SurfaceTextureListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: TextureView
    private lateinit var overlay: View
    private lateinit var statusView: TextView
    private lateinit var nameView: TextView
    private lateinit var addressView: TextView
    private lateinit var statusDot: View
    private lateinit var settingsButton: View
    private lateinit var rotationHint: TextView
    private var rotationDegrees = 0
    private var rightPanel: LinearLayout? = null
    private var showingSettings = false

    private var service: MirrorService? = null
    private var pendingSurface: Surface? = null
    private var approvalDialog: AlertDialog? = null
    private var videoWidth = 0
    private var videoHeight = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as MirrorService.LocalBinder).service()
            service = s
            s.registerUi(this@MainActivity)
            pendingSurface?.let { s.playback().setSurface(it) }
            refreshStatus()
            s.checkPendingApproval()
            rebuildRightPanel()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        val intent = Intent(this, MirrorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, MirrorService::class.java), connection, Context.BIND_AUTO_CREATE)
        settingsButton.requestFocus() // so OK on the remote opens Settings right away
    }

    override fun onStop() {
        super.onStop()
        service?.unregisterUi(this)
        unbindService(connection)
        // The service itself (and the AirPlay receiver in it) keeps running in the background.
    }

    private fun refreshStatus() {
        val s = service ?: return
        val addresses = s.addresses()
        if (addresses.isEmpty()) {
            setStatus(getString(R.string.status_no_network), RED)
            nameView.text = ""
            addressView.text = ""
            return
        }
        nameView.text = s.name()
        addressView.text = getString(R.string.status_address, TextUtils.join("  ·  ", addresses))
        setStatus(
            getString(if (s.isReady()) R.string.status_ready else R.string.status_starting),
            if (s.isReady()) GREEN else AMBER,
        )
    }

    // --- MirrorService.UiCallback (always delivered on the main thread) ---

    override fun onStatusChanged() = refreshStatus()

    override fun onMirroringStarted() = showOverlay(false)

    override fun onMirroringStopped() = showOverlay(true)

    override fun onVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        layoutVideo()
        showOverlay(false)
    }

    override fun onApprovalNeeded(deviceName: String) {
        approvalDialog?.dismiss()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassTheme.panel(this@MainActivity, 32f)
            setPadding(dp(40), dp(36), dp(40), dp(32))
        }

        box.addView(text(24, GlassTheme.TEXT_PRIMARY, true).apply {
            gravity = Gravity.START
            setText(R.string.approval_title)
        })
        box.addView(space(12))

        box.addView(text(17, GlassTheme.TEXT_SECONDARY, false).apply {
            gravity = Gravity.START
            text = getString(R.string.approval_message, deviceName)
        })
        box.addView(space(28))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val deny = text(17, Color.WHITE, true).apply {
            setText(R.string.approval_deny)
            isFocusable = true
            isClickable = true
            setPadding(dp(28), dp(12), dp(28), dp(12))
            background = GlassTheme.pillButton(this@MainActivity, 24f)
        }
        buttons.addView(deny)

        buttons.addView(View(this), LinearLayout.LayoutParams(dp(16), 1))

        val allow = text(17, Color.WHITE, true).apply {
            setText(R.string.approval_allow)
            isFocusable = true
            isClickable = true
            setPadding(dp(28), dp(12), dp(28), dp(12))
            background = GlassTheme.panelAccent(this@MainActivity, 24f, ACCENT)
        }
        buttons.addView(allow)

        box.addView(buttons)

        val dialog = AlertDialog.Builder(this)
            .setView(box)
            .setCancelable(false)
            .create()
        approvalDialog = dialog
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        deny.setOnClickListener {
            dialog.dismiss()
            service?.resolveApproval(false)
        }
        allow.setOnClickListener {
            dialog.dismiss()
            service?.resolveApproval(true)
        }
        dialog.show()
        allow.requestFocus()
    }

    // --- Surface ---

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val surface = Surface(texture)
        val s = service
        if (s != null) {
            s.playback().setSurface(surface)
        } else {
            pendingSurface = surface
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        pendingSurface = null
        service?.playback()?.setSurface(null)
        return true // we're done with it; let the system release it
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

    // --- Rotate (remote d-pad left/right while mirroring) ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.visibility != View.VISIBLE) { // only while a picture is actually showing
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                rotate(90)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                rotate(-90)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun rotate(deltaDegrees: Int) {
        rotationDegrees = ((rotationDegrees + deltaDegrees) % 360 + 360) % 360
        layoutVideo()
        rotationHint.text = getString(R.string.rotated, rotationDegrees)
        rotationHint.visibility = View.VISIBLE
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ rotationHint.visibility = View.GONE }, 1500)
    }

    // --- UI ---

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        surfaceView = TextureView(this).apply { surfaceTextureListener = this@MainActivity }
        root.addView(surfaceView, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo() }

        rotationHint = text(18, Color.WHITE, true).apply {
            background = GlassTheme.panel(this@MainActivity, 24f)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            visibility = View.GONE
        }
        val hintLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        hintLp.bottomMargin = dp(48)
        root.addView(rotationHint, hintLp)

        // Idle screen: a colourful glass backdrop with brand + receiver name on the left and
        // how-to steps on the right, each floating as its own translucent glass panel.
        val backdrop = FrameLayout(this)
        backdrop.addView(GlassTheme.buildBackground(this), FrameLayout.LayoutParams(MATCH, MATCH))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(72), dp(40), dp(72), dp(40))
        }

        // Left column
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassTheme.panel(this@MainActivity, 32f)
            setPadding(dp(40), dp(36), dp(40), dp(36))
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
                }
            }
        }
        brand.addView(logo, LinearLayout.LayoutParams(dp(64), dp(64)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        titles.addView(text(34, GlassTheme.TEXT_PRIMARY, true).apply {
            gravity = Gravity.START
            setText(R.string.app_name)
        })
        titles.addView(text(16, GlassTheme.TEXT_TERTIARY, false).apply {
            gravity = Gravity.START
            setText(R.string.tagline)
        })
        brand.addView(titles)
        left.addView(brand)
        left.addView(space(32))

        // Status chip
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassTheme.panelDim(this@MainActivity, 24f)
            setPadding(dp(16), dp(8), dp(20), dp(8))
        }
        statusDot = View(this).apply { background = oval(AMBER) }
        val dotLp = LinearLayout.LayoutParams(dp(12), dp(12))
        dotLp.rightMargin = dp(10)
        chip.addView(statusDot, dotLp)
        statusView = text(18, GlassTheme.TEXT_SECONDARY, false).apply { gravity = Gravity.START }
        chip.addView(statusView)
        left.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP))
        left.addView(space(28))

        // Receiver name card
        left.addView(text(14, GlassTheme.TEXT_TERTIARY, false).apply {
            gravity = Gravity.START
            letterSpacing = 0.15f
            setText(R.string.name_label)
        })
        left.addView(space(8))
        nameView = text(48, GlassTheme.TEXT_PRIMARY, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            background = GlassTheme.panelAccent(this@MainActivity, 22f, ACCENT)
            setPadding(dp(28), dp(16), dp(28), dp(16))
        }
        left.addView(nameView, LinearLayout.LayoutParams(MATCH, WRAP))
        left.addView(space(16))

        val settings = pillButton(R.string.settings)
        settings.setOnClickListener {
            showingSettings = !showingSettings
            rebuildRightPanel()
        }
        left.addView(settings, LinearLayout.LayoutParams(WRAP, WRAP))
        settingsButton = settings // first thing OK on the remote should reach
        left.addView(space(16))

        addressView = text(14, GlassTheme.TEXT_TERTIARY, false).apply { gravity = Gravity.START }
        left.addView(addressView)

        panel.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        panel.addView(View(this), LinearLayout.LayoutParams(dp(64), 1))

        // Right column: either "how to connect" or Settings, toggled by the button above -
        // one screen instead of a separate Settings Activity to navigate to and back from.
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassTheme.panel(this@MainActivity, 32f)
            setPadding(dp(36), dp(32), dp(36), dp(32))
        }
        rightPanel = right
        panel.addView(right, LinearLayout.LayoutParams(0, WRAP, 1f))
        rebuildRightPanel()

        backdrop.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))

        overlay = backdrop
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    /** Swaps the right-hand panel between "how to connect" and Settings, in place. */
    private fun rebuildRightPanel() {
        val panel = rightPanel ?: return
        panel.removeAllViews()
        if (showingSettings) {
            buildSettingsPanel(panel)
        } else {
            buildHowToConnectPanel(panel)
        }
    }

    private fun buildHowToConnectPanel(into: LinearLayout) {
        into.addView(text(22, GlassTheme.TEXT_PRIMARY, true).apply {
            gravity = Gravity.START
            setText(R.string.how_to)
        })
        into.addView(space(20))
        into.addView(step(1, getString(R.string.step_1)))
        into.addView(step(2, getString(R.string.step_2)))
        into.addView(step(3, getString(R.string.step_3)))
    }

    private fun buildSettingsPanel(into: LinearLayout) {
        into.addView(text(22, GlassTheme.TEXT_PRIMARY, true).apply {
            gravity = Gravity.START
            setText(R.string.settings_title)
        })
        into.addView(space(20))

        val s = service ?: return
        val current = s.connectMode()

        into.addView(
            settingsRow(R.string.rename_title, getString(R.string.settings_rename_subtitle, s.name())) {
                showRenameDialog()
            },
        )
        into.addView(space(24))

        into.addView(text(13, GlassTheme.TEXT_TERTIARY, false).apply {
            gravity = Gravity.START
            letterSpacing = 0.1f
            setText(R.string.settings_connect_section)
        })
        into.addView(space(10))

        into.addView(
            settingsOption(
                MirrorService.MODE_APPROVAL, R.string.settings_approval_title, R.string.settings_approval_subtitle, current,
            ),
        )
        into.addView(space(12))
        into.addView(
            settingsOption(MirrorService.MODE_PIN, R.string.settings_pin_title, R.string.settings_pin_subtitle, current),
        )
        into.addView(space(12))
        into.addView(
            settingsOption(MirrorService.MODE_NONE, R.string.settings_none_title, R.string.settings_none_subtitle, current),
        )
    }

    private fun settingsRow(titleRes: Int, subtitle: String, onClick: View.OnClickListener): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GlassTheme.focusableRow(this@MainActivity, 18f)
        }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(18, GlassTheme.TEXT_PRIMARY, true).apply {
            gravity = Gravity.START
            setText(titleRes)
        })
        texts.addView(text(13, GlassTheme.TEXT_SECONDARY, false).apply {
            gravity = Gravity.START
            text = subtitle
        })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

        val chevron = text(20, GlassTheme.TEXT_TERTIARY, false).apply { text = "›" }
        row.addView(chevron, LinearLayout.LayoutParams(dp(22), WRAP))

        row.setOnClickListener(onClick)
        return row
    }

    /** One selectable row for a connect mode; the PIN row is shown but not yet selectable. */
    private fun settingsOption(mode: String, titleRes: Int, subtitleRes: Int, current: String): View {
        val selected = mode == current
        val comingSoon = mode == MirrorService.MODE_PIN

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = if (selected) {
                GlassTheme.panelAccent(this@MainActivity, 18f, ACCENT)
            } else {
                GlassTheme.panelDim(this@MainActivity, 18f)
            }
            alpha = if (comingSoon) 0.6f else 1f
        }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(18, GlassTheme.TEXT_PRIMARY, true).apply {
            gravity = Gravity.START
            setText(titleRes)
        })
        texts.addView(text(13, GlassTheme.TEXT_SECONDARY, false).apply {
            gravity = Gravity.START
            text = if (comingSoon) {
                getString(subtitleRes) + getString(R.string.settings_coming_soon_suffix)
            } else {
                getString(subtitleRes)
            }
        })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

        if (selected) {
            val mark = text(20, Color.WHITE, true).apply { text = "✓" }
            row.addView(mark, LinearLayout.LayoutParams(dp(28), WRAP))
        }

        row.setOnClickListener {
            if (comingSoon) {
                Toast.makeText(this, R.string.settings_pin_toast, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            service?.let {
                it.setConnectMode(mode)
                rebuildRightPanel()
            }
        }
        return row
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            isSingleLine = true
            setText(service?.name() ?: "")
            setSelectAllOnFocus(true)
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(32))
            setTextColor(GlassTheme.TEXT_PRIMARY)
            setHintTextColor(GlassTheme.TEXT_TERTIARY)
            background = GlassTheme.panelDim(this@MainActivity, 16f)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val pad = dp(24)
        val box = FrameLayout(this).apply { setPadding(pad, dp(8), pad, 0) }
        box.addView(input)
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setMessage(R.string.rename_hint)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> applyRename(input.text.toString()) }
            .setNeutralButton(R.string.reset) { _, _ -> applyRename(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            dialog.dismiss()
            true
        }
        dialog.show()
    }

    private fun applyRename(rawName: String?) {
        val editor = getSharedPreferences("receiver", MODE_PRIVATE).edit()
        val name = rawName?.replace('.', ' ')?.trim().orEmpty()
        if (name.isEmpty()) {
            editor.remove("name")
        } else {
            editor.putString("name", name)
        }
        editor.apply()
        service?.restart()
        rebuildRightPanel()
    }

    /**
     * Letterbox the picture to fit the TV, then apply the manual rotation on top. A 90°/270°
     * rotation swaps which dimension is "wide", so we fit using the swapped video aspect and
     * give the (unrotated) view the swapped size back, so that after rotating it lands at the
     * fitted size.
     */
    private fun layoutVideo() {
        val pw = root.width
        val ph = root.height
        if (pw == 0 || ph == 0) return
        val swapped = rotationDegrees == 90 || rotationDegrees == 270
        val effVideoW = if (swapped) videoHeight else videoWidth
        val effVideoH = if (swapped) videoWidth else videoHeight

        var fitW = pw
        var fitH = ph
        if (effVideoW > 0 && effVideoH > 0) {
            if (effVideoW.toLong() * ph > effVideoH.toLong() * pw) {
                fitH = (pw.toLong() * effVideoH / effVideoW).toInt()
            } else {
                fitW = (ph.toLong() * effVideoW / effVideoH).toInt()
            }
        }

        val w = if (swapped) fitH else fitW
        val h = if (swapped) fitW else fitH
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            surfaceView.layoutParams = lp
        }
        surfaceView.rotation = rotationDegrees.toFloat()
    }

    private fun showOverlay(show: Boolean) {
        overlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            videoWidth = 0
            videoHeight = 0
            rotationDegrees = 0 // start each mirroring session unrotated
            layoutVideo()
        }
    }

    /** A small remote-focusable glass pill button, styled like Rename/Settings. */
    private fun pillButton(textRes: Int): TextView = text(18, Color.WHITE, true).apply {
        setText(textRes)
        isFocusable = true
        isClickable = true
        setPadding(dp(28), dp(12), dp(28), dp(12))
        background = GlassTheme.pillButton(this@MainActivity, 28f)
    }

    private fun text(sp: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        textSize = sp.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun space(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun setStatus(message: String, color: Int) {
        statusView.text = message
        statusDot.background = oval(color)
    }

    private fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** One numbered row of the how-to list. */
    private fun step(number: Int, message: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val badge = text(18, Color.WHITE, true).apply {
            text = number.toString()
            background = oval(ACCENT)
        }
        val badgeLp = LinearLayout.LayoutParams(dp(40), dp(40))
        badgeLp.rightMargin = dp(18)
        row.addView(badge, badgeLp)
        val label = text(19, GlassTheme.TEXT_SECONDARY, false).apply {
            gravity = Gravity.START
            text = message
        }
        row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        val AMBER = GlassTheme.AMBER
        val GREEN = GlassTheme.GREEN
        val RED = GlassTheme.RED
        val ACCENT = GlassTheme.ACCENT

        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
