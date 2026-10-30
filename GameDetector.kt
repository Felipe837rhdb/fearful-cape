package com.dlsreal

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process

/** Verifica se o usuário liberou "Acesso de uso" (necessário p/ saber qual app está aberto). */
object UsageAccess {
    @Suppress("DEPRECATION")
    fun granted(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val m = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return m == AppOpsManager.MODE_ALLOWED
    }
}

/** Descobre qual app está em primeiro plano lendo os eventos de uso do sistema. */
class ForegroundTracker(ctx: Context) {
    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val ignore = setOf(ctx.packageName, "com.android.systemui")
    private var lastTs = 0L
    private var fg: String? = null

    @Suppress("DEPRECATION")
    fun current(): String? {
        val now = System.currentTimeMillis()
        val from = if (lastTs == 0L) now - 2L * 60 * 60 * 1000 else lastTs - 1000
        val events = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val p = e.packageName ?: continue
            if (p in ignore) continue
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> fg = p
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (fg == p) fg = null
            }
        }
        lastTs = now
        return fg
    }
}

/** O app se declara jogo no sistema (categoria "Jogos" da loja ou isGame no manifesto)? */
object GameCheck {
    @Suppress("DEPRECATION")
    fun isGame(ctx: Context, pkg: String): Boolean = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    } catch (e: Exception) {
        false
    }
}
