package com.dlsreal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    companion object { private const val REQ_CAPTURE = 42; private const val REQ_MODEL = 43 }

    private var waitingOverlayPermission = false
    private lateinit var status: TextView
    private val prefs: SharedPreferences by lazy { getSharedPreferences("dlsreal", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0E0E10"))
        }
        root.addView(TextView(this).apply {
            text = "DLS Real"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "1) Escolha seus jogos.\n2) Permita o acesso de uso (para saber quando você entra no jogo).\n3) Toque em Ativar e, na janela de gravação, escolha \"Toda a tela\".\n\nNo modo AUTO os shaders ligam sozinhos só dentro dos jogos escolhidos e desligam fora deles."
            textSize = 14f
            setTextColor(Color.parseColor("#B0B0B8"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        })
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#FFB74D"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Escolher jogos"
            setOnClickListener { pickGames() }
        })
        root.addView(Button(this).apply {
            text = "Detectar jogos sozinho (liga/desliga)"
            setOnClickListener {
                prefs.edit().putBoolean("autoGames", !prefs.getBoolean("autoGames", true)).apply()
                refreshStatus()
            }
        })
        root.addView(Button(this).apply {
            text = "Permitir acesso de uso"
            setOnClickListener { openUsageSettings() }
        })
        root.addView(Button(this).apply {
            text = "Escolher modelo de IA (.tflite)"
            setOnClickListener { pickModel() }
        })
        root.addView(Button(this).apply {
            text = "Alternar motor (Shaders / IA)"
            setOnClickListener {
                val cur = prefs.getInt("engine", 0)
                if (cur == 0 && !File(filesDir, "model.tflite").exists()) {
                    Toast.makeText(this@MainActivity, "Escolha um modelo .tflite primeiro.", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putInt("engine", 1 - cur).apply()
                    refreshStatus()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Ativar"
            setOnClickListener { activate() }
        })
        root.addView(Button(this).apply {
            text = "Desativar"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        setContentView(root)
    }

    private fun refreshStatus() {
        val n = (prefs.getStringSet("games", emptySet()) ?: emptySet()).size
        val usage = if (UsageAccess.granted(this)) "permitido ✓" else "falta permitir"
        val eng = if (prefs.getInt("engine", 0) == 1) "IA" else "Shaders"
        val mf = File(filesDir, "model.tflite")
        val mdl = if (mf.exists()) "carregado (" + (mf.length() / 1024 / 1024) + " MB)" else "nenhum"
        val det = if (prefs.getBoolean("autoGames", true)) "sim" else "não"
        status.text = "Jogos escolhidos: $n  |  Detectar sozinho: $det\nAcesso de uso: $usage\nMotor: $eng  |  Modelo IA: $mdl\nO efeito (Shaders e IA) só liga dentro dos jogos."
    }

    @Suppress("DEPRECATION")
    private fun pickGames() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "Nenhum app encontrado.", Toast.LENGTH_LONG).show()
            return
        }
        val saved = prefs.getStringSet("games", emptySet()) ?: emptySet()
        val checked = BooleanArray(apps.size) { apps[it].first in saved }
        AlertDialog.Builder(this)
            .setTitle("Escolha seus jogos")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Salvar") { _, _ ->
                val set = HashSet<String>()
                for (i in apps.indices) if (checked[i]) set.add(apps[i].first)
                prefs.edit().putStringSet("games", set).apply()
                refreshStatus()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun pickModel() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, REQ_MODEL)
    }

    private fun openUsageSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        Toast.makeText(this, "Ache \"DLS Real\" na lista e permita.", Toast.LENGTH_LONG).show()
    }

    private fun activate() {
        if (!Settings.canDrawOverlays(this)) {
            waitingOverlayPermission = true
            Toast.makeText(this, "Permita \"Exibir sobre outros apps\" e volte.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        requestCapture()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        requestCapture()
    }

    private fun requestCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MODEL) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                Toast.makeText(this, "Copiando modelo...", Toast.LENGTH_SHORT).show()
                Thread {
                    try {
                        val tmp = File(filesDir, "model.tmp")
                        contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                        val dst = File(filesDir, "model.tflite")
                        if (dst.exists()) dst.delete()
                        tmp.renameTo(dst)
                        prefs.edit().putInt("modelVer", prefs.getInt("modelVer", 0) + 1).apply()
                        runOnUiThread {
                            Toast.makeText(this, "Modelo salvo. Use \"Alternar motor\" para a IA.", Toast.LENGTH_LONG).show()
                            refreshStatus()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this, "Falha ao copiar o modelo: " + e.message, Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
            return
        }
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            Toast.makeText(this, "Pronto! Abra o jogo: o efeito liga sozinho só dentro dele.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else if (requestCode == REQ_CAPTURE) {
            Toast.makeText(this, "A captura de tela é necessária para os shaders.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (waitingOverlayPermission && Settings.canDrawOverlays(this)) {
            waitingOverlayPermission = false
            activate()
        }
    }
}
