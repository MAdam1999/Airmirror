package com.airmirror.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.SurfaceTexture
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
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.airmirror.tv.MaterialStyle.PRIMARY
import com.airmirror.tv.MaterialStyle.PRIMARY_CONTAINER
import com.airmirror.tv.MaterialStyle.TEXT_PRIMARY
import com.airmirror.tv.MaterialStyle.TEXT_SECONDARY
import com.airmirror.tv.MaterialStyle.TEXT_TERTIARY

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
    private lateinit var statusChip: LinearLayout
    private lateinit var statusIcon: ImageView
    private lateinit var statusView: TextView
    private lateinit var nameView: TextView
    private lateinit var addressRow: View
    private lateinit var addressView: TextView
    private lateinit var settingsButton: View
    private lateinit var rotationHint: View
    private lateinit var rotationText: TextView
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
        MaterialStyle.apply(this, themeSetting())
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MaterialStyle.BACKGROUND))
        showingSettings = savedInstanceState?.getBoolean(STATE_SETTINGS) ?: false
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

    private enum class Status { STARTING, READY, NO_NETWORK }

    private fun refreshStatus() {
        val s = service ?: return
        val addresses = s.addresses()
        if (addresses.isEmpty()) {
            setStatus(getString(R.string.status_no_network), Status.NO_NETWORK)
            nameView.text = ""
            addressView.text = ""
            addressRow.visibility = View.GONE
            return
        }
        nameView.text = s.name()
        addressView.text = getString(R.string.status_address, TextUtils.join("  ·  ", addresses))
        addressRow.visibility = View.VISIBLE
        if (s.isReady()) {
            setStatus(getString(R.string.status_ready), Status.READY)
        } else {
            setStatus(getString(R.string.status_starting), Status.STARTING)
        }
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
            gravity = Gravity.CENTER_HORIZONTAL
            background = MaterialStyle.rounded(this@MainActivity, 28f, MaterialStyle.SURFACE)
            setPadding(dp(40), dp(36), dp(40), dp(28))
        }

        box.addView(MaterialStyle.circleIcon(this, R.drawable.ic_smartphone, 64f, 32f, PRIMARY_CONTAINER, PRIMARY))
        box.addView(space(20))
        box.addView(text(24, TEXT_PRIMARY, medium = true).apply {
            gravity = Gravity.CENTER
            setText(R.string.approval_title)
        })
        box.addView(space(12))
        box.addView(text(17, TEXT_SECONDARY).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.approval_message, deviceName)
        })
        box.addView(space(32))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val deny = MaterialStyle.iconButton(this, R.drawable.ic_close, getString(R.string.approval_deny), filled = false)
        val allow = MaterialStyle.iconButton(this, R.drawable.ic_check, getString(R.string.approval_allow), filled = true)
        buttons.addView(deny)
        buttons.addView(View(this), LinearLayout.LayoutParams(dp(16), 1))
        buttons.addView(allow)
        box.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP))

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
        rotationText.text = getString(R.string.rotated, rotationDegrees)
        rotationHint.visibility = View.VISIBLE
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ rotationHint.visibility = View.GONE }, 1500)
    }

    // --- UI ---

    private fun buildUi() {
        // Letterbox bars around the mirrored picture stay black, like any video player.
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        surfaceView = TextureView(this).apply { surfaceTextureListener = this@MainActivity }
        root.addView(surfaceView, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo() }

        // Rotation hint: a white Material pill with the screen-rotation icon.
        rotationText = text(17, TEXT_PRIMARY, medium = true)
        rotationHint = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MaterialStyle.rounded(this@MainActivity, 28f, MaterialStyle.SURFACE)
            setPadding(dp(20), dp(12), dp(24), dp(12))
            addView(MaterialStyle.icon(this@MainActivity, R.drawable.ic_screen_rotation, PRIMARY), iconLp(22, 12))
            addView(rotationText)
            visibility = View.GONE
        }
        MaterialStyle.elevate(this, rotationHint, 6f)
        val hintLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        hintLp.bottomMargin = dp(48)
        root.addView(rotationHint, hintLp)

        // Idle screen: white Material cards on a light background.
        val backdrop = FrameLayout(this).apply { setBackgroundColor(MaterialStyle.BACKGROUND) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(72), dp(40), dp(72), dp(40))
        }

        // Left card
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = MaterialStyle.card(this@MainActivity, 28f)
            setPadding(dp(40), dp(36), dp(40), dp(36))
        }
        MaterialStyle.elevate(this, left, 3f)

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(MaterialStyle.circleIcon(this, R.drawable.ic_airplay, 64f, 34f, PRIMARY, MaterialStyle.ON_PRIMARY))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, 0, 0)
        }
        titles.addView(text(32, TEXT_PRIMARY, medium = true).apply { setText(R.string.app_name) })
        titles.addView(space(6))
        titles.addView(text(16, TEXT_SECONDARY).apply { setText(R.string.tagline) })
        brand.addView(titles)
        left.addView(brand)
        left.addView(space(28))

        // Status chip: Material icon + label on a tinted container
        statusIcon = MaterialStyle.icon(this, R.drawable.ic_hourglass_empty, MaterialStyle.AMBER)
        statusView = text(17, MaterialStyle.AMBER, medium = true)
        statusChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MaterialStyle.rounded(this@MainActivity, 20f, MaterialStyle.AMBER_CONTAINER)
            setPadding(dp(14), dp(8), dp(18), dp(8))
            addView(statusIcon, iconLp(20, 8))
            addView(statusView)
        }
        left.addView(statusChip, LinearLayout.LayoutParams(WRAP, WRAP))
        left.addView(space(28))

        // Receiver name
        left.addView(text(13, TEXT_TERTIARY, medium = true).apply {
            letterSpacing = 0.12f
            setText(R.string.name_label)
        })
        left.addView(space(10))
        nameView = text(44, MaterialStyle.PRIMARY_DARK, medium = true).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            background = MaterialStyle.rounded(this@MainActivity, 20f, PRIMARY_CONTAINER)
            setPadding(dp(28), dp(18), dp(28), dp(18))
        }
        left.addView(nameView, LinearLayout.LayoutParams(MATCH, WRAP))
        left.addView(space(20))

        val settings = MaterialStyle.iconButton(this, R.drawable.ic_settings, getString(R.string.settings), filled = false)
        settings.setOnClickListener {
            showingSettings = !showingSettings
            rebuildRightPanel()
        }
        left.addView(settings, LinearLayout.LayoutParams(WRAP, WRAP))
        settingsButton = settings // first thing OK on the remote should reach
        left.addView(space(20))

        addressView = text(14, TEXT_SECONDARY)
        addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(MaterialStyle.icon(this@MainActivity, R.drawable.ic_wifi, TEXT_TERTIARY), iconLp(18, 8))
            addView(addressView)
        }
        left.addView(addressRow)

        panel.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        panel.addView(View(this), LinearLayout.LayoutParams(dp(48), 1))

        // Right card: either "how to connect" or Settings, toggled by the button above -
        // one screen instead of a separate Settings Activity to navigate to and back from.
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(32), dp(36), dp(32))
        }
        // Settings can be taller than the screen; the card scrolls as the remote moves focus.
        val rightCard = android.widget.ScrollView(this).apply {
            background = MaterialStyle.card(this@MainActivity, 28f)
            isVerticalScrollBarEnabled = false
            clipToOutline = true
            addView(right)
        }
        MaterialStyle.elevate(this, rightCard, 3f)
        rightPanel = right
        panel.addView(rightCard, LinearLayout.LayoutParams(0, WRAP, 1f))
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
        into.addView(text(22, TEXT_PRIMARY, medium = true).apply { setText(R.string.how_to) })
        into.addView(space(20))
        into.addView(step(R.drawable.ic_wifi, getString(R.string.step_1)))
        into.addView(step(R.drawable.ic_cast, getString(R.string.step_2)))
        into.addView(step(R.drawable.ic_tv, getString(R.string.step_3)))
    }

    private fun buildSettingsPanel(into: LinearLayout) {
        into.addView(text(22, TEXT_PRIMARY, medium = true).apply { setText(R.string.settings_title) })
        into.addView(space(20))

        val s = service ?: return
        val current = s.connectMode()

        into.addView(
            settingsRow(R.drawable.ic_edit, R.string.rename_title, getString(R.string.settings_rename_subtitle, s.name())) {
                showRenameDialog()
            },
        )
        into.addView(space(24))

        into.addView(text(13, TEXT_TERTIARY, medium = true).apply {
            letterSpacing = 0.1f
            setText(R.string.settings_connect_section)
        })
        into.addView(space(10))

        into.addView(
            settingsOption(
                MirrorService.MODE_APPROVAL, R.drawable.ic_verified_user,
                R.string.settings_approval_title, R.string.settings_approval_subtitle, current,
            ),
        )
        into.addView(space(10))
        into.addView(
            settingsOption(
                MirrorService.MODE_PIN, R.drawable.ic_lock,
                R.string.settings_pin_title, R.string.settings_pin_subtitle, current,
            ),
        )
        into.addView(space(10))
        into.addView(
            settingsOption(
                MirrorService.MODE_NONE, R.drawable.ic_lock_open,
                R.string.settings_none_title, R.string.settings_none_subtitle, current,
            ),
        )

        if (current == MirrorService.MODE_PIN) {
            into.addView(space(16))
            val digits = s.pin()?.length ?: 0
            into.addView(
                settingsRow(R.drawable.ic_lock, R.string.change_pin_title, getString(R.string.change_pin_subtitle, digits)) {
                    showPinDialog()
                },
            )
        }

        into.addView(space(24))
        into.addView(text(13, TEXT_TERTIARY, medium = true).apply {
            letterSpacing = 0.1f
            setText(R.string.settings_appearance_section)
        })
        into.addView(space(10))
        val theme = themeSetting()
        val themeIcon = when (theme) {
            MaterialStyle.THEME_DARK -> R.drawable.ic_dark_mode
            MaterialStyle.THEME_AUTO -> R.drawable.ic_brightness_auto
            else -> R.drawable.ic_light_mode
        }
        into.addView(
            settingsRow(themeIcon, R.string.appearance_title, getString(themeLabel(theme))) { showThemeDialog() },
        )
    }

    // --- Appearance (light / dark / automatic) ---

    private fun themeSetting(): String =
        getSharedPreferences("receiver", MODE_PRIVATE).getString("theme", MaterialStyle.THEME_LIGHT)
            ?: MaterialStyle.THEME_LIGHT

    private fun themeLabel(theme: String): Int = when (theme) {
        MaterialStyle.THEME_DARK -> R.string.theme_dark
        MaterialStyle.THEME_AUTO -> R.string.theme_auto
        else -> R.string.theme_light
    }

    private fun showThemeDialog() {
        val themes = arrayOf(MaterialStyle.THEME_LIGHT, MaterialStyle.THEME_DARK, MaterialStyle.THEME_AUTO)
        val labels = themes.map { getString(themeLabel(it)) }.toTypedArray()
        AlertDialog.Builder(this, MaterialStyle.DIALOG_STYLE)
            .setIcon(if (MaterialStyle.isDark) R.drawable.ic_dark_mode else R.drawable.ic_light_mode)
            .setTitle(R.string.appearance_title)
            .setSingleChoiceItems(labels, themes.indexOf(themeSetting()).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                if (themes[which] != themeSetting()) {
                    getSharedPreferences("receiver", MODE_PRIVATE).edit().putString("theme", themes[which]).apply()
                    recreate() // rebuild every view with the new palette; Settings stays open
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SETTINGS, showingSettings)
    }

    /** A Material list row: leading icon, two lines of text, trailing icon. */
    private fun listRow(
        leadingIcon: Int,
        leadingTint: Int,
        title: CharSequence,
        subtitle: CharSequence,
        trailingIcon: Int?,
        trailingTint: Int,
        selected: Boolean,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = if (selected) {
                MaterialStyle.focusableRow(this@MainActivity, 16f, PRIMARY_CONTAINER, PRIMARY_CONTAINER)
            } else {
                MaterialStyle.focusableRow(this@MainActivity, 16f)
            }
        }
        row.addView(MaterialStyle.icon(this, leadingIcon, leadingTint), iconLp(24, 18))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(18, TEXT_PRIMARY, medium = true).apply { text = title })
        texts.addView(space(4))
        texts.addView(text(14, TEXT_SECONDARY).apply { text = subtitle })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

        if (trailingIcon != null) {
            row.addView(MaterialStyle.icon(this, trailingIcon, trailingTint), iconLp(24, 0).apply { leftMargin = dp(12) })
        }
        return row
    }

    private fun settingsRow(iconRes: Int, titleRes: Int, subtitle: String, onClick: View.OnClickListener): View =
        listRow(iconRes, PRIMARY, getString(titleRes), subtitle, R.drawable.ic_chevron_right, TEXT_TERTIARY, false)
            .apply { setOnClickListener(onClick) }

    /** One selectable row for a connect mode (Material radio). Picking PIN without a PIN asks for one. */
    private fun settingsOption(mode: String, iconRes: Int, titleRes: Int, subtitleRes: Int, current: String): View {
        val selected = mode == current
        val subtitle = getString(subtitleRes)
        val row = listRow(
            iconRes,
            if (selected) PRIMARY else TEXT_SECONDARY,
            getString(titleRes),
            subtitle,
            if (selected) R.drawable.ic_radio_button_checked else R.drawable.ic_radio_button_unchecked,
            if (selected) PRIMARY else TEXT_TERTIARY,
            selected,
        )
        row.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (mode == MirrorService.MODE_PIN && s.pin() == null) {
                showPinDialog()
                return@setOnClickListener
            }
            s.setConnectMode(mode)
            rebuildRightPanel()
        }
        return row
    }

    /** Lets the owner choose the PIN iPhones must type; saving it also turns PIN mode on. */
    private fun showPinDialog() {
        val input = EditText(this).apply {
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(MirrorService.PIN_MAX_LENGTH))
            hint = "• • • •"
            gravity = Gravity.CENTER
            textSize = 28f
            letterSpacing = 0.3f
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_TERTIARY)
            background = MaterialStyle.focusableRow(this@MainActivity, 12f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0) }
        box.addView(input)

        val dialog = AlertDialog.Builder(this, MaterialStyle.DIALOG_STYLE)
            .setIcon(R.drawable.ic_lock)
            .setTitle(R.string.pin_dialog_title)
            .setMessage(R.string.pin_dialog_message)
            .setView(box)
            .setPositiveButton(R.string.save, null) // set below so a bad PIN keeps the dialog open
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun trySave() {
            val pin = input.text.toString()
            if (!MirrorService.isValidPin(pin)) {
                input.error = getString(R.string.pin_invalid)
                return
            }
            service?.setPin(pin)
            dialog.dismiss()
            Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show()
            rebuildRightPanel()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { trySave() }
        }
        input.setOnEditorActionListener { _, _, _ ->
            trySave()
            true
        }
        dialog.show()
        input.requestFocus()
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            isSingleLine = true
            setText(service?.name() ?: "")
            setSelectAllOnFocus(true)
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(32))
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_TERTIARY)
            background = MaterialStyle.focusableRow(this@MainActivity, 12f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val pad = dp(24)
        val box = FrameLayout(this).apply { setPadding(pad, dp(8), pad, 0) }
        box.addView(input)
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        val dialog = AlertDialog.Builder(this, MaterialStyle.DIALOG_STYLE)
            .setIcon(R.drawable.ic_edit)
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

    private fun setStatus(message: String, status: Status) {
        val (iconRes, color, container) = when (status) {
            Status.READY -> Triple(R.drawable.ic_check_circle, MaterialStyle.GREEN, MaterialStyle.GREEN_CONTAINER)
            Status.STARTING -> Triple(R.drawable.ic_hourglass_empty, MaterialStyle.AMBER, MaterialStyle.AMBER_CONTAINER)
            Status.NO_NETWORK -> Triple(R.drawable.ic_wifi_off, MaterialStyle.RED, MaterialStyle.RED_CONTAINER)
        }
        statusView.text = message
        statusView.setTextColor(color)
        statusIcon.setImageResource(iconRes)
        statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(color)
        statusChip.background = MaterialStyle.rounded(this, 20f, container)
    }

    /** One row of the how-to list: a Material icon in a tonal circle, then the instruction. */
    private fun step(iconRes: Int, message: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(MaterialStyle.circleIcon(this, iconRes, 48f, 24f, PRIMARY_CONTAINER, PRIMARY).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(20)
        })
        row.addView(text(18, TEXT_PRIMARY).apply { text = message }, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    private fun iconLp(sizeDp: Int, endMarginDp: Int) = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
        rightMargin = dp(endMarginDp)
    }

    private fun text(sp: Int, color: Int, medium: Boolean = false): TextView =
        MaterialStyle.text(this, sp.toFloat(), color, medium)

    private fun space(heightDp: Int): View = MaterialStyle.space(this, heightDp)

    private fun dp(value: Int): Int = MaterialStyle.dp(this, value.toFloat())

    private companion object {
        const val STATE_SETTINGS = "showing_settings"
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
