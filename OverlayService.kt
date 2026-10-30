package com.dlsreal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.dlsreal.STOP"
        private const val CHANNEL = "dlsreal"
        private const val NOTIF_ID = 7
        private const val SCALE = 0.5f // resolução da captura (0.5 = metade; menor = mais leve)
        private const val MODE_AUTO = 0 // liga sozinho só dentro dos jogos escolhidos
        private const val MODE_ON = 1   // sempre ligado
        private const val MODE_OFF = 2  // sempre desligado
        private const val POLL_MS = 500L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var currentSurface: Surface? = null
    private val renderer = EnhanceRenderer()

    private var glView: GLSurfaceView? = null
    private var tab: LinearLayout? = null
    private var toggle: TextView? = null
    private var active = false   // efeito realmente ligado agora
    private var mode = MODE_AUTO
    private var warnedUsage = false
    private var profile = ""                       // pacote do jogo atual ("" = perfil global)
    private val bars = HashMap<String, SeekBar>()
    private var profileLabel: TextView? = null
    private var engine = 0                // 0 = shaders, 1 = IA
    private var ai: AiRunner? = null
    private var loadedVer = 0
    private var engineChip: TextView? = null
    private val keys = arrayOf("ao", "spec", "refl", "light", "metal", "lamp")
    private lateinit var tracker: ForegroundTracker
    private val poll = object : Runnable {
        override fun run() { checkAi(); tick(); main.postDelayed(this, POLL_MS) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf(); return START_NOT_STICKY
        }
        startAsForeground()
        if (projection == null) {
            val code = intent.getIntExtra("code", 0)
            val data = intent.getParcelableExtra<Intent>("data")
            if (data == null) { stopSelf(); return START_NOT_STICKY }
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mgr.getMediaProjection(code, data)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSelf() } }
            }, main)
            projection = proj
            buildUi()
        }
        return START_NOT_STICKY
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "DLS Real", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("DLS Real ativo")
            .setContentText("Use a aba preta para ligar/desligar")
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Desativar", stop
                ).build()
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun realSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }

    private fun captureSize(): Pair<Int, Int> {
        val (w, h) = realSize()
        return (max(2, (w * SCALE).toInt()) and -2) to (max(2, (h * SCALE).toInt()) and -2)
    }

    private fun buildUi() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("dlsreal", MODE_PRIVATE)

        val (cw, ch) = captureSize()
        renderer.capW = cw; renderer.capH = ch
        applyPrefs()

        // ---- camada dos shaders (tela cheia, toques passam para o jogo) ----
        val gv = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderOnTop(true)
            preserveEGLContextOnPause = true
        }
        renderer.requestRender = { gv.requestRender() }
        renderer.postGl = { r -> gv.queueEvent(r) }
        renderer.onSurface = { s -> main.post { attachSurface(s) } }
        gv.setRenderer(renderer)
        gv.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        val glLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            glLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(gv, glLp)
        glView = gv

        // ---- aba preta pequena ----
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE000000"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tg = TextView(this).apply {
            textSize = 13f
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        toggle = tg
        val gear = TextView(this).apply {
            text = "⚙"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val close = TextView(this).apply {
            text = "✕"; textSize = 14f; setTextColor(Color.parseColor("#FF6B6B"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { stopSelf() }
        }
        row.addView(tg); row.addView(gear); row.addView(close)
        root.addView(row)

        val panel = buildPanel()
        root.addView(panel)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("tabX", dp(8))
            y = prefs.getInt("tabY", dp(120))
        }
        gear.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            wm.updateViewLayout(root, lp)
        }
        tab = root

        // arrastar pelo OFF/ON; toque simples liga/desliga
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tg.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { lp.x = startX + dx; lp.y = startY + dy; wm.updateViewLayout(root, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putInt("tabX", lp.x).putInt("tabY", lp.y).apply()
                    else cycleMode()
                    true
                }
                else -> false
            }
        }
        wm.addView(root, lp)
        mode = prefs.getInt("mode", MODE_AUTO)
        if (mode == MODE_ON) mode = MODE_AUTO // "sempre ligado" não existe mais
        tracker = ForegroundTracker(this)
        setActive(false)
        if (mode == MODE_AUTO) {
            val g = prefs.getStringSet("games", emptySet()) ?: emptySet()
            if (g.isEmpty() && !prefs.getBoolean("autoGames", true)) {
                Toast.makeText(this, "Escolha os jogos no app DLS Real: o efeito só liga dentro deles.", Toast.LENGTH_LONG).show()
            }
        }
        setEngine(prefs.getInt("engine", 0), false)
        main.postDelayed(poll, 300)
    }

    /** Chamado quando o GL cria a superfície que recebe a captura da tela. */
    private fun attachSurface(surface: Surface) {
        val proj = projection ?: return
        currentSurface = surface
        val (w, h) = captureSize()
        val dpi = resources.displayMetrics.densityDpi
        val existing = vd
        if (existing == null) {
            vd = proj.createVirtualDisplay(
                "dlsreal", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                if (active) surface else null, null, null
            )
        } else {
            existing.surface = if (active) surface else null
        }
    }

    private fun setActive(v: Boolean) {
        active = v
        renderer.enabled = v
        vd?.surface = if (v) currentSurface else null // pausa a captura quando desligado
        glView?.requestRender()
        updateLabel()
    }

    private fun updateLabel() {
        val txt: String
        val col: String
        when (mode) {
            MODE_ON -> { txt = "● ON"; col = "#4CAF50" }
            MODE_OFF -> { txt = "● OFF"; col = "#9E9E9E" }
            else -> if (active) { txt = "● AUTO ✓"; col = "#4CAF50" } else { txt = "● AUTO"; col = "#FFB74D" }
        }
        toggle?.apply {
            text = txt
            setTextColor(Color.parseColor(col))
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    /** Toque no rótulo: AUTO -> ON -> OFF -> AUTO */
    private fun cycleMode() {
        mode = if (mode == MODE_AUTO) MODE_OFF else MODE_AUTO
        prefs.edit().putInt("mode", mode).apply()
        setActive(false)
        if (mode == MODE_OFF) {
            Toast.makeText(this, "Desligado", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Automático: liga só dentro dos jogos", Toast.LENGTH_SHORT).show()
            tick()
        }
        updateLabel()
    }

    /** Modo AUTO: liga só quando o app em primeiro plano é um dos jogos escolhidos. */
    private fun tick() {
        if (mode != MODE_AUTO) return
        val games = prefs.getStringSet("games", emptySet()) ?: emptySet()
        var want = false
        if (!UsageAccess.granted(this)) {
            if (!warnedUsage) {
                warnedUsage = true
                Toast.makeText(this, "O AUTO precisa de \"Acesso de uso\". Abra o app DLS Real e toque em Permitir.", Toast.LENGTH_LONG).show()
            }
        } else {
            val fg = runCatching { tracker.current() }.getOrNull()
            val auto = prefs.getBoolean("autoGames", true)
            want = fg != null && (fg in games || (auto && isSystemGame(fg)))
            if (want && fg != null && fg != profile) loadProfile(fg)
        }
        if (!want && profile.isNotEmpty()) loadProfile("")
        if (want != active) setActive(want)
    }

    private val gameCache = HashMap<String, Boolean>()
    private fun isSystemGame(pkg: String): Boolean =
        gameCache.getOrPut(pkg) { GameCheck.isGame(this, pkg) }

    private fun startAi() {
        val f = File(filesDir, "model.tflite")
        val r = AiRunner(f.absolutePath)
        ai = r
        renderer.ai = r
        loadedVer = prefs.getInt("modelVer", 0)
        r.load()
    }

    private fun stopAi() {
        renderer.ai = null
        ai?.release()
        ai = null
    }

    /** Troca entre motor de shaders (0) e motor de IA (1). */
    private fun setEngine(e: Int, save: Boolean) {
        var eng = e
        if (eng == 1) {
            if (!File(filesDir, "model.tflite").exists()) {
                Toast.makeText(this, "Escolha um modelo .tflite no app DLS Real para usar o motor IA.", Toast.LENGTH_LONG).show()
                eng = 0
            } else if (ai == null) {
                startAi()
            }
        }
        if (eng == 0) stopAi()
        engine = eng
        renderer.engine = eng
        if (save || eng != e) prefs.edit().putInt("engine", eng).apply()
        engineChip?.text = if (eng == 1) "Motor: IA  (toque p/ trocar)" else "Motor: Shaders  (toque p/ trocar)"
        glView?.requestRender()
    }

    /** Roda a cada ciclo: reage a mudança de motor feita no app, falha da IA e troca de modelo. */
    private fun checkAi() {
        val want = prefs.getInt("engine", 0)
        if (want != engine) { setEngine(want, false); return }
        val r = ai ?: return
        if (engine == 1 && r.failed) {
            Toast.makeText(this, "IA falhou: " + r.error + ". Voltando aos shaders.", Toast.LENGTH_LONG).show()
            setEngine(0, true)
            return
        }
        if (prefs.getInt("modelVer", 0) != loadedVer) { stopAi(); startAi() }
    }

    private fun pk(k: String) = if (profile.isEmpty()) k else "p_" + profile + "_" + k
    private fun getV(k: String) = prefs.getInt(pk(k), prefs.getInt(k, 60))

    private fun applyPrefs() {
        renderer.ao = getV("ao") / 60f
        renderer.spec = getV("spec") / 60f
        renderer.refl = getV("refl") / 60f
        renderer.lightK = getV("light") / 60f
        renderer.metal = getV("metal") / 60f
        renderer.lamp = getV("lamp") / 60f
        renderer.aiStr = getV("aistr") / 60f
    }

    /** Cada jogo guarda seus próprios ajustes; sem ajuste salvo, usa o perfil global. */
    private fun loadProfile(p: String) {
        profile = p
        applyPrefs()
        for ((k, bar) in bars) bar.progress = getV(k)
        val name = if (p.isEmpty()) "Global" else runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
        }.getOrDefault(p)
        profileLabel?.text = "Perfil: $name"
    }

    private fun applyPreset(vals: IntArray) {
        val ed = prefs.edit()
        for (i in keys.indices) {
            ed.putInt(pk(keys[i]), vals[i])
            bars[keys[i]]?.progress = vals[i]
        }
        ed.apply()
    }

    private fun chip(label: String, vals: IntArray): TextView = TextView(this).apply {
        text = label; textSize = 11f; setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#33FFFFFF")); cornerRadius = dp(10).toFloat()
        }
        setPadding(dp(8), dp(5), dp(8), dp(5))
        setOnClickListener { applyPreset(vals) }
    }

    private fun buildPanel(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            minimumWidth = dp(240)
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        val pl = TextView(this).apply {
            text = "Perfil: Global"; textSize = 11f; setTextColor(Color.parseColor("#4CAF50"))
        }
        profileLabel = pl
        box.addView(pl)
        val ec = TextView(this).apply {
            textSize = 12f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#554CAF50")); cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { setEngine(if (engine == 1) 0 else 1, true) }
        }
        engineChip = ec
        box.addView(ec, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) })
        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        // ordem: ao, spec, refl, light, metal, lamp
        val lpChip = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = dp(6) }
        presets.addView(chip("3D realista", intArrayOf(70, 60, 50, 60, 75, 60)), lpChip)
        presets.addView(chip("2D / desenho", intArrayOf(20, 40, 0, 45, 15, 40)), lpChip)
        presets.addView(chip("Neon / noite", intArrayOf(50, 60, 40, 70, 50, 100)), lpChip)
        box.addView(presets)
        box.addView(seekRow("Sombras de contato", "ao") { renderer.ao = it / 60f })
        box.addView(seekRow("Brilho / metal / detalhe", "spec") { renderer.spec = it / 60f })
        box.addView(seekRow("Reflexo chão / água", "refl") { renderer.refl = it / 60f })
        box.addView(seekRow("Luz dinâmica / cor", "light") { renderer.lightK = it / 60f })
        box.addView(seekRow("Metal (brilho / reflexo)", "metal") { renderer.metal = it / 60f })
        box.addView(seekRow("Lâmpadas / halo de luz", "lamp") { renderer.lamp = it / 60f })
        box.addView(seekRow("Força da IA (só motor IA)", "aistr") { renderer.aiStr = it / 60f })
        box.addView(TextView(this).apply {
            text = "Toque no rótulo: AUTO (só dentro do jogo) ↔ OFF"
            textSize = 10f; setTextColor(Color.parseColor("#888888"))
            setPadding(0, dp(8), 0, 0)
        })
        return box
    }

    private fun seekRow(label: String, key: String, onChange: (Int) -> Unit): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        col.addView(TextView(this).apply {
            text = label; textSize = 11f; setTextColor(Color.parseColor("#BBBBBB"))
        })
        col.addView(SeekBar(this).apply {
            max = 100; progress = getV(key)
            bars[key] = this
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { prefs.edit().putInt(pk(key), s.progress).apply() }
            })
        })
        return col
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection == null) return
        main.postDelayed({
            val (w, h) = captureSize()
            renderer.resize(w, h)
            runCatching { vd?.resize(w, h, resources.displayMetrics.densityDpi) }
        }, 300)
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        stopAi()
        runCatching { vd?.release() }
        runCatching { projection?.stop() }
        runCatching { glView?.let { wm.removeView(it) } }
        runCatching { tab?.let { wm.removeView(it) } }
        vd = null; projection = null; glView = null; tab = null
        super.onDestroy()
    }
}
