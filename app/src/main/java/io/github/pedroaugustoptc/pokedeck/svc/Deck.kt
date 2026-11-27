package io.github.pedroaugustoptc.pokedeck.svc

import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.pedroaugustoptc.pokedeck.core.Config
import io.github.pedroaugustoptc.pokedeck.core.Jitter
import io.github.pedroaugustoptc.pokedeck.core.Log
import io.github.pedroaugustoptc.pokedeck.core.Timing
import io.github.pedroaugustoptc.pokedeck.net.Http
import io.github.pedroaugustoptc.pokedeck.net.KickApi
import io.github.pedroaugustoptc.pokedeck.net.TwitchApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * O coração do app, vivo enquanto o processo vive (a tela e o serviço só olham para cá): a config, as 4 contas, a
 * conferência de quem está no ar e onde ficam os navegadores escondidos. O [DeckService] só segura o processo acordado.
 */
object Deck {
    lateinit var app: Context
        private set
    lateinit var config: Config
        private set
    lateinit var accounts: List<Account>
        private set
    lateinit var bridgeScript: String
        private set
    lateinit var keepScript: String
        private set
    val scope = MainScope()
    val parking = Parking()

    /** A conta aberta na tela (-1: o menu). */
    var shown = -1
        private set
    var twitchLive: Map<String, Boolean?> = emptyMap()
        private set
    var kickLive: Map<String, Boolean?> = emptyMap()
        private set
    private var checking = false
    private val configFile get() = File(app.filesDir, "config.json")

    val ready get() = ::config.isInitialized

    /** Uma vez por processo (a tela ou o serviço reiniciado pelo Android, o que vier primeiro). */
    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        Log.filePath = File(app.filesDir, "pokedeck.log")
        Log.redeemPath = File(app.filesDir, "resgates.log")
        Log.loadRedeems()
        Log.write(null, "abrindo o PokeDeck")
        config = Config.load(configFile)
        bridgeScript = Web.asset(app, "game-bridge.js")
        keepScript = Web.asset(app, "page-keep.js")
        accounts = (0 until Config.MAX_ACCOUNTS).map { Account(it) }
        if (Web.supported()) Http.userAgent = Web.defaultUa(app)
        scope.launch {
            delay(15_000)
            while (true) {
                checkLives()
                delay(Jitter.sec(Timing.LIVE_CHECK_SEC))
            }
        }
        scope.launch {
            while (true) {
                delay(60_000)
                accounts.filter { it.game != null }.forEach { Web.flush(it.profile) }
            }
        }
    }

    fun saveConfig() = config.save(configFile)

    /** Liga/desliga as contas conforme a config e o serviço junto (sem conta ligada, nada fica rodando). */
    fun sync() {
        if (!Web.supported()) return
        for (a in accounts) {
            val on = config.accounts[a.index].on
            if (on && a.game == null) a.start()
            if (!on && a.game != null) {
                if (shown == a.index) shown = -1
                a.stop()
            }
        }
        DeckService.update(app, accounts.count { it.game != null })
    }

    /** A tela salvou a Config (ou um ajuste rápido): grava, aplica e confere as lives se mudou algo delas. */
    fun setConfig(next: Config) {
        val old = config
        config = next.fix()
        saveConfig()
        val started = accounts.any { config.accounts[it.index].on && it.game == null }
        sync()
        accounts.forEach { it.applyPrefs() }
        val livesChanged = started || old.twitchChannels != config.twitchChannels || old.kickChannels != config.kickChannels ||
            old.twitchLives != config.twitchLives || old.kickLives != config.kickLives ||
            old.accounts.map { it.lives } != config.accounts.map { it.lives }
        if (livesChanged) scope.launch { checkLives() }
    }

    /** Muda a config viva no lugar (botões da conta, ajustes rápidos) e aplica. */
    fun edit(change: (Config) -> Unit) {
        val next = config.copy()
        change(next)
        setConfig(next)
    }

    /** O botão "Parar" da notificação: desliga tudo agora, sem mexer na config (abre de novo ao abrir o app). */
    fun stopAll() {
        shown = -1
        accounts.forEach { it.stop() }
        DeckService.update(app, 0)
    }

    /** Quem está ao vivo (Twitch numa consulta só; Kick canal a canal, com pausas) e repassa às contas. */
    suspend fun checkLives() {
        if (checking || accounts.none { it.game != null && config.accounts[it.index].lives }) return
        checking = true
        try {
            twitchLive = TwitchApi.live(config.twitchChannels)
            val kick = LinkedHashMap<String, Boolean?>()
            for (c in config.kickChannels) {
                kick[c] = KickApi.isLive(c)
                delay(Jitter.between(400, 1500))
            }
            kickLive = kick
            for (a in accounts) a.updateLives(twitchLive, kickLive)
        } finally {
            checking = false
        }
    }

    // ---------------- conta na tela ----------------

    /** Move o jogo da conta para a tela (sem recarregar: é o mesmo navegador). */
    fun show(index: Int, container: ViewGroup) {
        val v = accounts[index].game ?: return
        if (shown >= 0 && shown != index) hide()
        (v.parent as? ViewGroup)?.removeView(v)
        (v.context as MutableContextWrapper).baseContext = container.context
        container.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shown = index
        accounts[index].applyPerf()
    }

    /** Devolve o jogo da tela para o estacionamento escondido (continua rodando). */
    fun hide() {
        val i = shown
        if (i < 0) return
        shown = -1
        val v = accounts[i].game ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        (v.context as MutableContextWrapper).baseContext = app
        parking.park(v)
        accounts[i].applyPerf()
    }
}

/**
 * Onde ficam os navegadores escondidos. Com a permissão "sobrepor a outros apps": uma janela de 1 px quase transparente,
 * que não recebe toque, com cada navegador em tamanho de celular lá dentro (cortado). Assim o Chromium os vê como
 * visíveis e não pausa nada com o app fechado. Sem a permissão, ficam num canto da tela do app enquanto ela existe.
 */
class Parking {
    private var overlay: FrameLayout? = null
    /** O canto de 1 px da tela do app (quando não há permissão de sobrepor). */
    var fallback: ViewGroup? = null
        set(value) {
            field = value
            repark()
        }

    val hasOverlay get() = overlay != null

    private fun ensureOverlay(): ViewGroup? {
        overlay?.let { return it }
        val ctx = Deck.app
        if (!Settings.canDrawOverlays(ctx)) return null
        val box = FrameLayout(ctx)
        val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.01f
        }
        return try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(box, lp)
            overlay = box
            box
        } catch (e: Exception) {
            Log.write(null, "sem a janela escondida: ${e.message}")
            null
        }
    }

    fun park(v: View) {
        (v.parent as? ViewGroup)?.removeView(v)
        val target = ensureOverlay() ?: fallback ?: return // sem lugar: fica solto (e o Android pode pausar)
        val dm = Deck.app.resources.displayMetrics
        target.addView(v, FrameLayout.LayoutParams(minOf(dm.widthPixels, dm.heightPixels), maxOf(dm.widthPixels, dm.heightPixels)))
    }

    /** A permissão chegou ou a tela mudou: leva os escondidos para o melhor lugar. */
    fun repark() {
        for (a in Deck.accounts) {
            val v = a.game ?: continue
            if (Deck.shown != a.index && v.parent !== overlay) park(v)
        }
    }
}
