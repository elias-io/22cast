package dev.elias.cast22

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.graphics.*
import android.graphics.drawable.*
import android.os.*
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject
import java.util.Date
import kotlin.math.min
import kotlin.math.roundToInt

/** Native Android TV interface. The desktop HTML interface is not used by this activity. */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private val ink = Color.rgb(240, 240, 223)
    private val muted = Color.rgb(153, 158, 142)
    private val accent = Color.rgb(231, 173, 98)
    private val background = Color.rgb(16, 18, 16)
    private val panel = Color.rgb(27, 30, 26)
    private val line = Color.argb(32, 240, 240, 223)
    private val ui = Handler(Looper.getMainLooper())
    private var scale = 1f
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private var decoder: VideoDecoder? = null
    private var videoWidth = 1920
    private var videoHeight = 1080
    private lateinit var home: FrameLayout
    private lateinit var receiverName: TextView
    private lateinit var eyebrow: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var networkClock: TextView
    private lateinit var qualityCorner: TextView
    private lateinit var dock: LinearLayout
    private lateinit var dockArea: LinearLayout
    private lateinit var streamBar: LinearLayout
    private lateinit var senderLabel: TextView
    private lateinit var disconnect: Button
    private var modal: Dialog? = null
    private var modalName = ""
    private var pinDialog: Dialog? = null
    private var displayedPin = ""
    private var streaming = false
    private var streamControlsShown = false
    private var wakeKey = KeyEvent.KEYCODE_UNKNOWN
    private var wakeTouch = false
    private var current = JSONObject()
    private var pendingUpdate: ReleaseUpdate? = null
    private var updateOffered = false
    private var resumed = false
    private var lastDockId = R.id.dock_connect
    private val networkFields = mutableMapOf<String, TextView>()
    private var pauseButton: Button? = null
    private val immersive = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

    private val hideStreamControls = Runnable {
        if (streaming) hideStreamBar(animate = true)
    }

    private val updater = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            ReceiverService.instance?.let { render(it.snapshot()) }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = immersive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val metrics = resources.displayMetrics
        scale = min(metrics.widthPixels / 1280f, metrics.heightPixels / 720f).coerceAtLeast(.5f)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); isFocusable = true; isFocusableInTouchMode = true
        }
        surface = SurfaceView(this).apply { id = R.id.video_surface; holder.addCallback(this@MainActivity) }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        buildHome()
        buildStreamBar()
        setContentView(root)
        root.post { findViewById<View>(R.id.dock_connect).requestFocus() }
        val service = Intent(this, ReceiverService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::onBackPressed)
        checkForUpdate()
    }

    private fun px(value: Int) = (value * scale).roundToInt()
    private fun rounded(color: Int, radius: Int = 12, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = px(radius).toFloat()
        if (stroke != 0) setStroke(px(1).coerceAtLeast(1), stroke)
    }
    private fun label(value: String, size: Int, color: Int = ink, alignment: Int = Gravity.START) = TextView(this).apply {
        text = value; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size).toFloat())
        gravity = alignment; includeFontPadding = false
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private fun setLabel(view: TextView, value: String) { if (view.text.toString() != value) view.text = value }
    private fun vertical() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
    }
    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        clipChildren = false; clipToPadding = false
    }
    private fun space(parent: LinearLayout, height: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, px(height))) }
    private fun section(parent: LinearLayout, text: String) {
        parent.addView(label(text.uppercase(), 10, muted).apply { letterSpacing = .17f },
            LinearLayout.LayoutParams(-1, px(27)))
    }
    private fun button(value: String, identifier: Int = View.generateViewId(), primary: Boolean = false): Button = Button(this).apply {
        id = identifier; text = value; isAllCaps = false; isFocusable = true; isFocusableInTouchMode = true
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; elevation = 0f
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14).toFloat())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(px(17), 0, px(17), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(accent).apply { setStroke(px(2), ink) })
            addState(intArrayOf(android.R.attr.state_pressed), rounded(accent))
            addState(intArrayOf(), rounded(if (primary) accent else Color.rgb(43, 49, 38), 10, line))
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(this@MainActivity.background, if (primary) this@MainActivity.background else ink)))
        setOnFocusChangeListener { view, focused ->
            view.animate().scaleX(if (focused) 1.025f else 1f).scaleY(if (focused) 1.025f else 1f).setDuration(100).start()
        }
    }

    private fun buildHome() {
        home = FrameLayout(this).apply { id = R.id.home }
        home.addView(object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(this@MainActivity.background)
                paint.shader = RadialGradient(width / 2f, height * .35f, width * .5f,
                    intArrayOf(Color.rgb(30, 35, 24), this@MainActivity.background), null, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint); paint.shader = null
            }
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        val brand = horizontal()
        brand.addView(CastMark(), LinearLayout.LayoutParams(px(34), px(32)))
        brand.addView(label("22Cast", 25).apply { setPadding(px(13), 0, px(22), 0); typeface = Typeface.create("sans-serif-medium", 0) })
        brand.addView(label("A LITTLE CLOSER.", 10, muted).apply { letterSpacing = .2f })
        home.addView(brand, FrameLayout.LayoutParams(px(470), px(42), Gravity.TOP or Gravity.START).apply {
            leftMargin = px(68); topMargin = px(38)
        })
        networkClock = label("Local network", 13, muted, Gravity.END or Gravity.CENTER_VERTICAL)
        home.addView(networkClock, FrameLayout.LayoutParams(px(370), px(42), Gravity.TOP or Gravity.END).apply {
            rightMargin = px(68); topMargin = px(38)
        })
        val hero = vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
        hero.addView(CastMark(), LinearLayout.LayoutParams(px(146), px(122)))
        space(hero, 31)
        eyebrow = label("A SMALL SCREEN. A BIGGER MOMENT.", 10, muted, Gravity.CENTER).apply { letterSpacing = .27f }
        hero.addView(eyebrow, LinearLayout.LayoutParams(-1, px(24)))
        receiverName = label("22Cast Living Room", 50, ink, Gravity.CENTER).apply {
            id = R.id.receiver_name; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; letterSpacing = -.045f
        }
        hero.addView(receiverName, LinearLayout.LayoutParams(-1, px(76)))
        subtitle = label("Open Screen Mirroring on your iPhone, iPad or Mac.", 14, muted, Gravity.CENTER)
        hero.addView(subtitle, LinearLayout.LayoutParams(-1, px(27)))
        space(hero, 21)
        status = label("Starting receiver…", 12, Color.rgb(185, 201, 170), Gravity.CENTER).apply {
            id = R.id.status_label; setPadding(px(17), px(9), px(17), px(9))
            background = rounded(Color.rgb(25, 31, 23), 25, Color.rgb(53, 65, 44)); maxLines = 2
        }
        hero.addView(status, LinearLayout.LayoutParams(-2, -2))
        home.addView(hero, FrameLayout.LayoutParams(px(1120), px(400), Gravity.CENTER).apply { bottomMargin = px(50) })
        dockArea = vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
        dock = horizontal().apply { setPadding(px(8), px(8), px(8), px(8)); background = rounded(Color.rgb(25, 29, 23), 17, line) }
        val entries = listOf("How to connect" to R.id.dock_connect, "Network" to R.id.dock_network,
            "Settings" to R.id.dock_settings, "About" to R.id.dock_about)
        entries.forEachIndexed { index, (title, identifier) ->
            val item = button(title, identifier).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13).toFloat())
                setPadding(px(8), 0, px(8), 0)
                setOnClickListener { lastDockId = identifier; when (identifier) {
                    R.id.dock_connect -> showConnect(); R.id.dock_network -> showNetwork()
                    R.id.dock_settings -> showSettings(); else -> showAbout()
                } }
                setOnKeyListener { _, key, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                        root.findViewById<View>(entries[(index + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else 3) % 4].second).requestFocus(); true
                    } else false
                }
                nextFocusUpId = identifier; nextFocusDownId = identifier
            }
            dock.addView(item, LinearLayout.LayoutParams(0, px(56), 1f).apply { if (index > 0) leftMargin = px(5) })
        }
        dockArea.addView(dock, LinearLayout.LayoutParams(-1, px(74)))
        dockArea.addView(label("AIRPLAY     ·     SAME NETWORK     ·     NO CABLES", 9, muted, Gravity.CENTER).apply { letterSpacing = .13f },
            LinearLayout.LayoutParams(-1, px(34)))
        home.addView(dockArea, FrameLayout.LayoutParams(px(650), px(110), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(34) })
        home.addView(label("DESIGNED FOR YOUR LIVING ROOM\nLess setup. More sharing.", 9, muted).apply { setLineSpacing(px(5).toFloat(), 1f) },
            FrameLayout.LayoutParams(px(260), px(38), Gravity.BOTTOM or Gravity.START).apply { leftMargin = px(68); bottomMargin = px(46) })
        qualityCorner = label("1080p · 30 fps", 10, muted, Gravity.END or Gravity.CENTER_VERTICAL)
        home.addView(qualityCorner, FrameLayout.LayoutParams(px(200), px(38), Gravity.BOTTOM or Gravity.END).apply { rightMargin = px(68); bottomMargin = px(46) })
    }

    private fun buildStreamBar() {
        streamBar = horizontal().apply {
            id = R.id.stream_bar; gravity = Gravity.CENTER; setPadding(px(22), px(9), px(10), px(9))
            background = rounded(Color.argb(239, 25, 29, 23), 13, line); visibility = View.GONE
        }
        val text = vertical()
        senderLabel = label("Screen mirroring", 14)
        text.addView(senderLabel); space(text, 4); text.addView(label("Connected to 22Cast", 10, muted))
        streamBar.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        disconnect = button("Disconnect", R.id.disconnect).apply { setOnClickListener { command("disconnect") } }
        streamBar.addView(disconnect, LinearLayout.LayoutParams(px(122), px(46)).apply { leftMargin = px(25) })
        root.addView(streamBar, FrameLayout.LayoutParams(px(410), px(68), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(22) })
    }

    private fun render(state: JSONObject) {
        current = state
        val wasStreaming = streaming
        streaming = state.optBoolean("streaming")
        val video = streaming && state.optBoolean("video")
        home.visibility = if (video) View.GONE else View.VISIBLE
        dockArea.visibility = if (streaming) View.GONE else View.VISIBLE
        if (!streaming) {
            hideStreamBar()
            wakeKey = KeyEvent.KEYCODE_UNKNOWN; wakeTouch = false
        }
        setLabel(receiverName, if (streaming && !video) state.optString("sender").ifBlank { state.optString("name", "22Cast Living Room") } else state.optString("name", "22Cast Living Room"))
        setLabel(eyebrow, if (streaming) "NOW PLAYING" else "A SMALL SCREEN. A BIGGER MOMENT.")
        setLabel(subtitle, if (streaming) "Audio from your Apple device" else "Open Screen Mirroring on your iPhone, iPad or Mac.")
        setLabel(status, state.optString("message", "Starting receiver…"))
        status.setTextColor(if (state.optString("status") == "error") accent else Color.rgb(185, 201, 170))
        setLabel(qualityCorner, state.optString("quality", "1080") + "p · " + state.optInt("fps", 30) + " fps")
        setLabel(networkClock, state.optString("network", "Local network") + "     |     " + android.text.format.DateFormat.getTimeFormat(this).format(Date()))
        setLabel(senderLabel, state.optString("sender").ifBlank { "Screen mirroring" })
        if (state.optBoolean("keepAwake", true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (streaming && !wasStreaming) { modal?.dismiss(); showStreamControls() }
        if (!streaming && wasStreaming) findViewById<View>(lastDockId).requestFocus()
        if (modalName == "network") updateNetwork(state)
        showPin(state.optString("pin"))
        offerPendingUpdate()
    }

    private fun checkForUpdate() {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        Thread({
            val release = UpdateCheck.fetch(code, Build.VERSION.SDK_INT)
            ui.post {
                if (!isFinishing && !isDestroyed) { pendingUpdate = release; offerPendingUpdate() }
            }
        }, "22Cast update check").apply { isDaemon = true; start() }
    }

    private fun offerPendingUpdate() {
        val release = pendingUpdate ?: return
        if (updateOffered || !resumed || streaming || current.length() == 0 || !hasWindowFocus() ||
            modal?.isShowing == true || pinDialog?.isShowing == true || current.optString("pin").isNotBlank()) return
        updateOffered = true
        pendingUpdate = null
        dialog("An update is available", "22Cast ${release.versionName}", 400) { panel, close ->
            val body = scroll(panel)
            body.addView(label("A newer version of 22Cast is ready to download.", 16))
            if (release.releaseNotes.isNotBlank()) {
                space(body, 16)
                body.addView(label(release.releaseNotes, 13, muted).apply {
                    setLineSpacing(px(5).toFloat(), 1f); isFocusable = true
                })
            }
            space(body, 14)
            body.addView(label("Your receiver keeps working while you decide. Installation is always your choice.", 12, muted))
            val later = button("Later", R.id.update_later).apply { setOnClickListener { modal?.dismiss() } }
            val download = button("Download update", R.id.update_download, primary = true).apply {
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
                        modal?.dismiss()
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "Open 22cast.elias.dev on your phone or computer to download the update.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            actions(panel, later, download); wireVertical(close, later, download)
            panel.post { later.requestFocus() }
        }
    }

    private fun showStreamControls() {
        if (!streaming) return
        if (!streamControlsShown) {
            streamControlsShown = true
            val animation = streamBar.animate().withEndAction(null)
            animation.cancel()
            if (streamBar.visibility != View.VISIBLE) {
                streamBar.alpha = 0f
                streamBar.translationY = px(14).toFloat()
                streamBar.visibility = View.VISIBLE
            }
            animation.alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.5f)).start()
            disconnect.requestFocus()
        }
        ui.removeCallbacks(hideStreamControls)
        ui.postDelayed(hideStreamControls, 5_000)
    }

    private fun hideStreamBar(animate: Boolean = false) {
        ui.removeCallbacks(hideStreamControls)
        streamControlsShown = false
        val hadFocus = streamBar.hasFocus()
        val animation = streamBar.animate().withEndAction(null)
        animation.cancel()
        if (animate && streamBar.visibility == View.VISIBLE) {
            animation.alpha(0f).translationY(px(14).toFloat()).setDuration(180)
                .setInterpolator(AccelerateInterpolator()).withEndAction {
                    if (!streamControlsShown) streamBar.visibility = View.GONE
                }.start()
        } else {
            streamBar.visibility = View.GONE
            streamBar.alpha = 0f
            streamBar.translationY = px(14).toFloat()
        }
        if (hadFocus) root.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Consume the whole wake-up press so revealing Disconnect cannot activate it.
        if (wakeKey != KeyEvent.KEYCODE_UNKNOWN && event.keyCode == wakeKey) {
            if (streaming) showStreamControls()
            if (event.action == KeyEvent.ACTION_UP) wakeKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (streaming && event.action == KeyEvent.ACTION_DOWN && event.keyCode in intArrayOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)) {
            val wasHidden = !streamControlsShown
            showStreamControls()
            if (wasHidden) { wakeKey = event.keyCode; return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (streaming) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) wakeTouch = !streamControlsShown
            showStreamControls()
            if (wakeTouch) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) wakeTouch = false
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun command(action: String, data: JSONObject = JSONObject()): Boolean = try {
        val service = ReceiverService.instance ?: error("Receiver is starting")
        service.command(action, data); render(service.snapshot()); true
    } catch (error: Exception) {
        Toast.makeText(this, error.message ?: "Could not apply this change", Toast.LENGTH_SHORT).show(); false
    }

    private fun dialog(title: String, tag: String, height: Int = 590, acceptsText: Boolean = false, content: (LinearLayout, Button) -> Unit) {
        modal?.dismiss()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = vertical().apply { setPadding(px(30), px(25), px(30), px(25)); background = rounded(panel, 22, line) }
        val heading = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val titles = vertical()
        titles.addView(label(tag.uppercase(), 10, accent).apply { letterSpacing = .18f }); space(titles, 9)
        titles.addView(label(title, 30).apply { id = R.id.dialog_title })
        heading.addView(titles, FrameLayout.LayoutParams(-1, -1).apply { rightMargin = px(55) })
        val close = button("×", R.id.close_dialog).apply {
            contentDescription = "Close dialog"; setPadding(0, 0, 0, 0); setOnClickListener { dialog.dismiss() }
        }
        heading.addView(close, FrameLayout.LayoutParams(px(36), px(36), Gravity.END or Gravity.CENTER_VERTICAL))
        body.addView(heading, LinearLayout.LayoutParams(-1, px(66)))
        content(body, close)
        dialog.setContentView(body)
        dialog.setOnDismissListener {
            if (modal === dialog) { modal = null; modalName = ""; pauseButton = null; networkFields.clear() }
            if (!streaming) findViewById<View>(lastDockId).requestFocus()
        }
        modal = dialog; modalName = title.lowercase()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.78f); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(min(px(650), resources.displayMetrics.widthPixels - px(64)), min(px(height), resources.displayMetrics.heightPixels - px(64)))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                if (acceptsText) WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            decorView.systemUiVisibility = immersive
        }
        close.requestFocus()
    }

    private fun scroll(parent: LinearLayout): LinearLayout {
        val body = vertical()
        val scroll = ScrollView(this).apply {
            isFillViewport = false; isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
        }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2)); parent.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return body
    }
    private fun actions(parent: LinearLayout, vararg buttons: Button) {
        val row = horizontal().apply { gravity = Gravity.END; setPadding(px(2), px(15), px(2), px(2)) }
        buttons.forEachIndexed { index, button -> row.addView(button, LinearLayout.LayoutParams(-2, px(45)).apply { if (index > 0) leftMargin = px(14) }) }
        parent.addView(row, LinearLayout.LayoutParams(-1, px(64)))
    }
    private fun wireVertical(vararg fields: View) {
        fields.forEachIndexed { index, view ->
            view.nextFocusDownId = fields[(index + 1) % fields.size].id
            view.nextFocusUpId = fields[(index + fields.size - 1) % fields.size].id
            if (view is EditText) view.setOnKeyListener { _, key, event ->
                if (key in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A) &&
                    event.flags and KeyEvent.FLAG_EDITOR_ACTION == 0) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) showNameKeyboard(view)
                    true
                } else if (event.action == KeyEvent.ACTION_DOWN && !keyboardVisible(view) &&
                    (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP)) {
                    fields[(index + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else fields.size - 1) % fields.size].requestFocus(); true
                } else false
            }
        }
    }
    private fun showNameKeyboard(editor: EditText) {
        if (!editor.requestFocus()) return
        // A remote Select is an explicit request. SHOW_IMPLICIT may be ignored on TVs.
        editor.post {
            if (editor.isAttachedToWindow && editor.hasWindowFocus() && editor.hasFocus())
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, 0)
        }
    }
    private fun keyboardVisible(editor: EditText): Boolean {
        if (Build.VERSION.SDK_INT >= 30)
            return editor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        val visible = Rect()
        editor.getWindowVisibleDisplayFrame(visible)
        return resources.displayMetrics.heightPixels - visible.bottom > px(100)
    }
    private fun row(parent: LinearLayout, title: String, control: View, detail: String = "") {
        val row = horizontal().apply { background = rounded(Color.TRANSPARENT, 0, line); setPadding(px(2), 0, px(2), 0) }
        val labels = vertical()
        labels.addView(label(title, 14).apply { labelFor = control.id })
        if (detail.isNotBlank()) { space(labels, 4); labels.addView(label(detail, 10, muted)) }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(control, LinearLayout.LayoutParams(px(120), px(39)).apply { leftMargin = px(18) })
        parent.addView(row, LinearLayout.LayoutParams(-1, px(51)))
    }
    private fun option(identifier: Int, choices: List<String>, initial: String, suffix: String): Button {
        var selected = choices.indexOf(initial).coerceAtLeast(0)
        val button = button(choices[selected] + suffix, identifier)
        button.tag = choices[selected]
        fun change(delta: Int) { selected = (selected + delta + choices.size) % choices.size; button.tag = choices[selected]; button.text = choices[selected] + suffix }
        button.setOnClickListener { change(1) }
        button.setOnKeyListener { _, key, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                change(if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1); true
            } else false
        }
        button.contentDescription = if (identifier == R.id.quality) "Resolution. Press left or right to change." else "Frame rate. Press left or right to change."
        return button
    }
    private fun toggle(identifier: Int, enabled: Boolean): Switch = Switch(this).apply {
        id = identifier; isChecked = enabled; isFocusable = true; isFocusableInTouchMode = true; showText = false
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        thumbTintList = ColorStateList.valueOf(ink)
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, Color.rgb(67, 74, 58)))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(Color.argb(50, 231, 173, 98), 10, accent))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        setPadding(px(8), 0, px(8), 0)
        setOnKeyListener { _, key, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                isChecked = key == KeyEvent.KEYCODE_DPAD_RIGHT; true
            } else false
        }
    }

    private fun showSettings() {
        val state = ReceiverService.instance?.snapshot() ?: current
        dialog("Settings", "Make yourself at home", 640, acceptsText = true) { panel, close ->
            val body = scroll(panel)
            section(body, "This screen")
            body.addView(label("AirPlay name", 13, muted), LinearLayout.LayoutParams(-1, px(22)))
            val name = EditText(this).apply {
                id = R.id.device_name; setSingleLine(true); setText(state.optString("name", "22Cast Living Room"))
                setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_PX, px(17).toFloat())
                setPadding(px(13), 0, px(13), 0); background = rounded(Color.rgb(19, 22, 17), 8, Color.rgb(69, 77, 59))
                filters = arrayOf(InputFilter.LengthFilter(50)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                setSelectAllOnFocus(true)
                setOnClickListener { showNameKeyboard(this) }
            }
            body.addView(name, LinearLayout.LayoutParams(-1, px(43))); space(body, 12)
            section(body, "Picture & sound")
            val quality = option(R.id.quality, listOf("1080", "720"), state.optString("quality", "1080"), "p")
            name.setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(name.windowToken, 0)
                    quality.requestFocus()
                    true
                } else false
            }
            val fps = option(R.id.fps, listOf("30", "60"), state.optInt("fps", 30).toString(), " fps")
            val audio = toggle(R.id.audio, state.optBoolean("audio", true))
            row(body, "Resolution", quality); row(body, "Frame rate", fps); row(body, "Play audio", audio)
            space(body, 8); section(body, "Connection & display")
            val pin = toggle(R.id.pin_required, state.optBoolean("pinRequired"))
            val awake = toggle(R.id.keep_awake, state.optBoolean("keepAwake", true))
            row(body, "Require a PIN", pin); row(body, "Keep screen awake", awake)
            space(body, 12); body.addView(label("Saving receiver settings restarts the connection.", 11, muted))
            val cancel = button("Cancel", R.id.cancel_settings).apply { setOnClickListener { modal?.dismiss() } }
            val save = button("Save changes", R.id.save_settings, true).apply {
                setOnClickListener {
                    val data = JSONObject().put("name", name.text.toString().trim()).put("quality", quality.tag.toString())
                        .put("fps", fps.tag.toString().toInt()).put("audio", audio.isChecked).put("pinRequired", pin.isChecked).put("keepAwake", awake.isChecked)
                    if (command("settings", data)) { modal?.dismiss(); Toast.makeText(this@MainActivity, "Settings saved", Toast.LENGTH_SHORT).show() }
                }
            }
            actions(panel, cancel, save); wireVertical(close, name, quality, fps, audio, pin, awake, cancel, save)
            cancel.nextFocusRightId = save.id; save.nextFocusLeftId = cancel.id
        }
    }

    private fun showConnect() {
        dialog("A bigger view, in three steps.", "Start with your small screen", 570) { panel, close ->
            val body = scroll(panel)
            val steps = listOf("Join the same network" to "Connect your Apple device and this screen to the same Wi-Fi or local network.",
                "Open Screen Mirroring" to "On iPhone or iPad, open Control Center and tap the overlapping rectangles. On Mac, open Control Center → Screen Mirroring.",
                "Choose " + current.optString("name", "22Cast Living Room") to "Your screen will appear here. Choose Stop Mirroring on your device when you finish.")
            steps.forEachIndexed { index, (title, description) ->
                space(body, 20)
                body.addView(label("0${index + 1}    $title", 18, if (index == 2) accent else ink))
                space(body, 9); body.addView(label(description, 14, muted).apply { setLineSpacing(px(4).toFloat(), 1f) })
            }
            space(body, 20); body.addView(label("Some streaming services restrict protected video. Start with photos, presentations or ordinary screen sharing.", 11, muted))
            val done = button("Got it", primary = true).apply { setOnClickListener { modal?.dismiss() } }
            actions(panel, done); wireVertical(close, done)
        }
    }

    private fun showNetwork() {
        dialog("Network", "Stay connected", 520) { panel, close ->
            val body = scroll(panel)
            for ((key, title) in listOf("network" to "Connection", "ip" to "IP address", "name" to "AirPlay name", "message" to "Receiver")) {
                val value = label("—", 14, ink, Gravity.END)
                val row = horizontal(); row.addView(label(title, 14, muted), LinearLayout.LayoutParams(0, px(52), 1f))
                row.addView(value, LinearLayout.LayoutParams(0, px(52), 2f)); body.addView(row)
                networkFields[key] = value
            }
            space(body, 20); body.addView(label("Your devices need to be on the same local network. Guest Wi-Fi and device isolation can prevent discovery.", 13, muted))
            val pause = button("Pause receiver", R.id.action_pause).apply { setOnClickListener { command(if (current.optString("status") == "paused") "resume" else "pause") } }
            pauseButton = pause
            val restart = button("Restart receiver", R.id.action_restart, true).apply { setOnClickListener { command("restart") } }
            actions(panel, pause, restart); wireVertical(close, pause, restart)
        }
        updateNetwork(ReceiverService.instance?.snapshot() ?: current)
    }
    private fun updateNetwork(state: JSONObject) {
        networkFields.forEach { (key, view) -> setLabel(view, state.optString(key, "—")) }
        pauseButton?.let { setLabel(it, if (state.optString("status") == "paused") "Resume receiver" else "Pause receiver") }
    }

    private fun showAbout() {
        dialog("A little closer.", "Small screen, meet big screen.", 510) { panel, close ->
            val body = scroll(panel)
            body.addView(label("22Cast", 48, accent)); space(body, 15)
            body.addView(label("Your photos. Your ideas. Your people.\nA quiet place to bring them together on the big screen.", 16, muted))
            space(body, 24)
            val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            body.addView(label("Version $version", 13)); space(body, 13)
            body.addView(label("AirPlay screen mirroring + audio", 13)); space(body, 23)
            body.addView(label("22Cast is an independent open-source project. AirPlay is a trademark of Apple Inc.", 11, muted))
            val licenses = button("Open-source licenses", R.id.about_licenses).apply { setOnClickListener { showLicenses() } }
            val done = button("Back to the big screen", primary = true).apply { setOnClickListener { modal?.dismiss() } }
            actions(panel, licenses, done); wireVertical(close, licenses, done)
        }
    }
    private fun showLicenses() {
        dialog("Open-source licenses", "22Cast", 620) { panel, close ->
            val body = scroll(panel)
            body.addView(label("UxPlay / PlayFair — GPL-3.0\nOpenSSL — Apache-2.0\nlibplist / FFmpeg — LGPL-2.1 or later\nKotlin — Apache-2.0", 15, ink).apply { setLineSpacing(px(7).toFloat(), 1f) })
            space(body, 18)
            val notice = assets.open("licenses/THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
            body.addView(label(notice, 12, muted).apply { setLineSpacing(px(4).toFloat(), 1f); isFocusable = true; id = View.generateViewId() })
            val done = button("Done", primary = true).apply { setOnClickListener { modal?.dismiss() } }
            actions(panel, done); wireVertical(close, body.getChildAt(body.childCount - 1), done)
        }
    }
    private fun showPin(pin: String) {
        if (pin.isBlank()) { pinDialog?.dismiss(); pinDialog = null; displayedPin = ""; return }
        if (pin == displayedPin && pinDialog?.isShowing == true) return
        pinDialog?.dismiss(); modal?.dismiss(); displayedPin = pin
        val body = vertical().apply { gravity = Gravity.CENTER; setPadding(px(32), px(28), px(32), px(28)); background = rounded(panel, 20, line) }
        body.addView(label("Enter this code on your device", 15, muted, Gravity.CENTER))
        space(body, 20); body.addView(label(pin, 60, accent, Gravity.CENTER).apply { id = R.id.pin_code; letterSpacing = .22f })
        space(body, 22)
        val cancel = button("Cancel connection").apply { setOnClickListener { command("disconnect") } }
        body.addView(cancel, LinearLayout.LayoutParams(-2, px(45)))
        pinDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(body)
            setOnCancelListener { command("disconnect") }; show()
            window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setDimAmount(.8f); setLayout(px(470), px(280)); decorView.systemUiVisibility = immersive }
        }
        cancel.requestFocus()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        decoder = VideoDecoder(holder.surface) { width, height -> runOnUiThread { videoWidth = width; videoHeight = height; fitVideo() } }
        ReceiverService.videoSink = decoder
    }
    private fun fitVideo() {
        if (root.width <= 0 || root.height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val fit = min(root.width.toFloat() / videoWidth, root.height.toFloat() / videoHeight)
        surface.layoutParams = FrameLayout.LayoutParams((videoWidth * fit).roundToInt(), (videoHeight * fit).roundToInt(), Gravity.CENTER)
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { ReceiverService.videoSink = null; decoder?.close(); decoder = null }
    override fun onResume() { super.onResume(); resumed = true; ui.post(updater); if (streaming) showStreamControls() }
    override fun onPause() {
        resumed = false
        ui.removeCallbacks(updater); hideStreamBar()
        wakeKey = KeyEvent.KEYCODE_UNKNOWN; wakeTouch = false
        super.onPause()
    }
    override fun onBackPressed() {
        if (modal?.isShowing == true) { modal?.dismiss(); return }
        if (streaming) {
            AlertDialog.Builder(this).setTitle("Disconnect this device?").setNegativeButton("Keep casting", null)
                .setPositiveButton("Disconnect") { _, _ -> command("disconnect") }.show(); return
        }
        AlertDialog.Builder(this).setTitle("Close 22Cast?").setMessage("The receiver will stop accepting connections.")
            .setNegativeButton("Stay", null).setPositiveButton("Close") { _, _ -> stopService(Intent(this, ReceiverService::class.java)); finish() }.show()
    }
    override fun onDestroy() {
        ui.removeCallbacks(updater); hideStreamBar(); modal?.dismiss(); pinDialog?.dismiss()
        ReceiverService.videoSink = null; decoder?.close(); decoder = null
        super.onDestroy()
    }

    private inner class CastMark : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 4.5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas); canvas.save(); canvas.scale(width / 120f, height / 100f)
            paint.style = Paint.Style.STROKE
            val screen = Path().apply { moveTo(12f, 22f); lineTo(12f, 16f); quadTo(12f, 10f, 18f, 10f); lineTo(106f, 10f); quadTo(112f, 10f, 112f, 16f); lineTo(112f, 81f); quadTo(112f, 87f, 106f, 87f); lineTo(70f, 87f) }
            canvas.drawPath(screen, paint)
            canvas.drawArc(RectF(-34f, 45f, 54f, 133f), 270f, 90f, false, paint)
            canvas.drawArc(RectF(-15f, 64f, 35f, 114f), 270f, 90f, false, paint)
            paint.style = Paint.Style.FILL; canvas.drawCircle(10f, 89f, 4.5f, paint); canvas.restore()
        }
    }
}
