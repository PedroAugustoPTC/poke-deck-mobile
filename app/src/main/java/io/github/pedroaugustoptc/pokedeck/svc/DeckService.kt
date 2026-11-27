package io.github.pedroaugustoptc.pokedeck.svc

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.github.pedroaugustoptc.pokedeck.R
import io.github.pedroaugustoptc.pokedeck.core.Log
import io.github.pedroaugustoptc.pokedeck.ui.MainActivity

/**
 * Segura o processo acordado enquanto há conta ligada: serviço em primeiro plano (sem limite de horas, ao contrário
 * do dataSync do Android 15), trava de CPU e de Wi-Fi e a notificação. START_STICKY: se o Android matar o processo,
 * ele volta sozinho e o [Deck] religa as contas com a sessão que ficou gravada.
 */
class DeckService : Service() {
    companion object {
        private const val CHANNEL = "deck"
        private const val ID = 1
        private const val ACTION_STOP = "stop"

        /** Liga, atualiza ou desliga o serviço conforme o número de contas rodando. */
        fun update(context: Context, running: Int) {
            val i = Intent(context, DeckService::class.java)
            if (running > 0) {
                try {
                    context.startForegroundService(i)
                } catch (e: Exception) { // Android 12+: sem tela aberta e sem isenção de bateria, não deixa
                    Log.write(null, "o Android não deixou ligar o serviço: ${e.message}")
                }
            } else context.stopService(i)
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout") // fica enquanto houver conta jogando; solta no onDestroy
    override fun onCreate() {
        super.onCreate()
        Deck.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Contas rodando", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PokeDeck:contas").apply { acquire() }
        @Suppress("DEPRECATION") // HIGH_PERF segue valendo com a tela apagada até o 13; no 14+ o serviço já segura a rede
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PokeDeck:contas")
            .apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.write(null, "parado pela notificação")
            Deck.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val running = Deck.accounts.count { it.game != null }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(running), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, notification(running))
        } catch (e: Exception) {
            Log.write(null, "o Android não deixou o serviço em primeiro plano: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) Deck.sync() // reiniciado pelo Android depois de matar o processo: religa as contas
        return START_STICKY
    }

    private fun notification(running: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DeckService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("PokeDeck")
            .setContentText(if (running == 1) "1 conta jogando" else "$running contas jogando")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Deck.accounts.filter { it.game != null }.forEach { Web.flush(it.profile) }
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
