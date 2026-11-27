package io.github.pedroaugustoptc.pokedeck.svc

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import io.github.pedroaugustoptc.pokedeck.core.GameState
import io.github.pedroaugustoptc.pokedeck.core.Guard
import io.github.pedroaugustoptc.pokedeck.core.Jitter
import io.github.pedroaugustoptc.pokedeck.core.Log
import io.github.pedroaugustoptc.pokedeck.core.Points
import io.github.pedroaugustoptc.pokedeck.core.RedeemBlock
import io.github.pedroaugustoptc.pokedeck.core.Restock
import io.github.pedroaugustoptc.pokedeck.core.Timing
import io.github.pedroaugustoptc.pokedeck.net.KickApi
import io.github.pedroaugustoptc.pokedeck.net.KickWatcher
import io.github.pedroaugustoptc.pokedeck.net.TwitchApi
import io.github.pedroaugustoptc.pokedeck.net.TwitchPubSub
import io.github.pedroaugustoptc.pokedeck.net.TwitchWatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Uma conta (porte do Account.cs do PC, sem voto e extensões): a página do jogo (perfil próprio), a recompra,
 * ULTRA/Economia/Mudo, o vigia de "jogo não abriu", as lives leves (watchers), o baú e os resgates da Twitch/Kick e a
 * guarda do bônus. Tudo na thread principal, como no PC.
 */
class Account(val index: Int) {
    companion object {
        const val GAME_URL = "https://pokeidle.io/app"
        val ORIGINS = setOf("https://pokeidle.io", "https://*.pokeidle.io")
        private const val AGENT_IDLE_MS = 10 * 60_000L
        private val ddhhmm = DateTimeFormatter.ofPattern("dd/MM HH:mm")
        fun siteName(site: String) = if (site == "twitch") "Twitch" else "Kick"
    }

    val profile = "conta${index + 1}"
    var game: WebView? = null
        private set
    var state: GameState? = null
        private set
    /** off, loading, login, game, failed, elsewhere. */
    var phase = "off"
        private set
    var twitchOpen: List<String> = emptyList()
        private set
    var kickOpen: List<String> = emptyList()
        private set

    private val cfg get() = Deck.config.accounts[index]
    private val restock = Restock().apply { note = { Log.write(index, "recompra: $it") } }
    val tp = Points("Twitch")
    val kp = Points("Kick")
    private val tg = Guard()
    private val kg = Guard()
    private val twitchIds = HashMap<String, String>() // id do canal → login (os avisos vêm por id)
    private var scope: CoroutineScope? = null
    private var prefsScript: ScriptHandler? = null
    private var ultraOn = false
    private var economyOn = false
    private var mutedOn = true
    private var loadedAt = System.currentTimeMillis()
    private var phaseSince = System.currentTimeMillis()
    private var reloads = 0
    private var agent: AgentPage? = null
    private var tw: TwitchWatcher? = null
    private var kw: KickWatcher? = null
    private var ps: TwitchPubSub? = null
    private var twitchToken: String? = null
    private var twitchDevice: String? = null
    private var kickToken: String? = null
    private var busyTwitch = false
    private var busyKick = false
    private val redeemSeen = HashMap<String, Boolean>()
    private val blockIgnoredUntil = HashMap<String, LocalDateTime>()

    val pubsubError get() = ps?.error

    // ---------------- jogo ----------------

    fun start() {
        if (game != null) return
        val v = Web.create(Deck.app, profile)
        game = v
        scope = MainScope()
        phase = "loading"
        WebViewCompat.addWebMessageListener(v, "pdBridge", ORIGINS) { _, message, _, isMainFrame, _ ->
            if (isMainFrame) message.data?.let { onBridge(it) }
        }
        WebViewCompat.addDocumentStartJavaScript(v, Deck.keepScript, ORIGINS)
        WebViewCompat.addDocumentStartJavaScript(v, Deck.bridgeScript, ORIGINS)
        v.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loadedAt = System.currentTimeMillis()
                setPhase("loading")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loadedAt = System.currentTimeMillis()
                applyPerf()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) setPhase("failed")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // O processo do navegador caiu (falta de memória, quase sempre): recria a conta, como o ProcessFailed do PC.
                Log.write(index, "o navegador da conta caiu; recriando")
                stop(log = false)
                Deck.scope.launch {
                    delay(Jitter.between(2_000, 6_000))
                    if (cfg.on) start()
                }
                return true
            }
        }
        applyPrefs(firstTime = true)
        Deck.parking.park(v)
        v.loadUrl(GAME_URL)

        every(10_000, { Jitter.of(10_000, 0.3) }) { watchdog() }
        every(40_000, { Jitter.sec(Timing.TWITCH_POINTS_SEC) }) { twitchPoints() }
        every(60_000, { Jitter.sec(Timing.KICK_POINTS_SEC) }) { kickPoints() }
        every(90_000, { Jitter.sec(Timing.GUARD_SEC) }) { guard("twitch"); guard("kick") }
        every(60_000, { 60_000 }) { dropIdleAgent() }
        Log.write(index, "conta ligada")
    }

    /** Roda [work] para sempre no escopo da conta, com a espera de [period] (relida a cada volta). */
    private fun every(firstMs: Long, period: () -> Long, work: suspend () -> Unit) {
        scope?.launch {
            delay(firstMs)
            while (true) {
                try {
                    work()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.write(index, "erro: ${e.message}")
                }
                delay(period())
            }
        }
    }

    fun stop(log: Boolean = true) {
        scope?.cancel()
        scope = null
        stopLives()
        agent?.destroy()
        agent = null
        game?.let {
            Web.flush(profile)
            Web.destroy(it)
            if (log) Log.write(index, "conta desligada")
        }
        game = null
        prefsScript = null
        state = null
        phase = "off"
    }

    fun reload() {
        val v = game ?: return
        loadedAt = System.currentTimeMillis()
        // Saiu do jogo por um link? Volta para o jogo em vez de recarregar a outra página.
        if (Uri.parse(v.url ?: "").host?.endsWith("pokeidle.io") == true) v.reload() else v.loadUrl(GAME_URL)
    }

    private fun setPhase(p: String) {
        if (p == phase) return
        phase = p
        phaseSince = System.currentTimeMillis()
        if (p == "game") {
            reloads = 0
            Web.flush(profile) // entrou no jogo: a sessão vai para o disco já
        }
    }

    /** Recarrega o jogo preso na abertura (ou que não carregou), esperando mais a cada vez: 1, 2 … 5 min. */
    private fun watchdog() {
        if (game == null || phase in setOf("game", "login", "elsewhere")) return
        val wait = minOf(5, reloads + 1) * 60_000L
        val now = System.currentTimeMillis()
        if (now - loadedAt < wait || now - phaseSince < wait) return
        reloads++
        Log.write(index, "o jogo não abriu ($phase); recarregando (tentativa $reloads)")
        reload()
    }

    private fun onBridge(json: String) {
        val m = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return
        }
        when (m.optString("t")) {
            "state" -> {
                val s = GameState.parse(m)
                state = s
                setPhase(m.optString("phase", "loading"))
                restock.tick(s).forEach { send(it) }
            }
            "bought" -> restock.bought(m.optLong("qtd"), m.optLong("gasto"))
            "refused" -> restock.refused(m.optString("msg"))
        }
    }

    /** FPS do jogo: na tela, um pouco mais; escondida, o mínimo (a caça não muda, só o desenho). */
    fun applyPerf() {
        val fps = if (Deck.shown == index) Timing.FPS_SHOWN else Timing.FPS_HIDDEN
        game?.evaluateJavascript("window.__pdFps=$fps;window.__pdSpriteMs=${Timing.SPRITE_MS}", null)
    }

    private fun send(message: String) =
        game?.evaluateJavascript("window.__pd && window.__pd.send(${JSONObject.quote(message)})", null)

    /**
     * ULTRA/Economia são escolhas do próprio jogo no localStorage ("cfg-ultra", "cfg-economia"): o script entra antes do
     * jogo carregar. Mudou com a conta aberta: grava e recarrega (nenhum clique sintético). Mudo: corta o som na página.
     */
    fun applyPrefs(firstTime: Boolean = false) {
        val v = game ?: return
        restock.configure(cfg.restock.copy())
        applyPerf()
        for (site in listOf("twitch", "kick")) {
            val on = redeemOn(site)
            // Religado à mão com o buff já cobrindo o bloqueio (a tela avisou): resgata até o horário de bloqueio passar.
            val left = buffLeft(site)
            if (!firstTime && on && redeemSeen[site] == false && left != null && RedeemBlock.covers(Deck.config.redeemBlockAt, left, LocalDateTime.now())) {
                blockIgnoredUntil[site] = RedeemBlock.next(Deck.config.redeemBlockAt, LocalDateTime.now())!!
                Log.redeem(index, siteName(site), "—", "bloqueio", "resgate religado à mão; o bloqueio das ${Deck.config.redeemBlockAt} volta a valer depois desse horário")
            }
            redeemSeen[site] = on
        }
        val changed = !firstTime && (cfg.ultra != ultraOn || cfg.economy != economyOn)
        if (changed) {
            // Só grava o que mudou: desligado no app = devolve ao jogo o "0" uma vez, depois o jogo decide.
            val sets = ArrayList<String>()
            if (cfg.ultra != ultraOn) sets += "localStorage.setItem('cfg-ultra','${if (cfg.ultra) 1 else 0}')"
            if (cfg.economy != economyOn) sets += "localStorage.setItem('cfg-economia','${if (cfg.economy) 1 else 0}')"
            v.evaluateJavascript("try{${sets.joinToString(";")}}catch(e){}", null)
        }
        if (firstTime || changed || cfg.muted != mutedOn) {
            prefsScript?.remove()
            val keep = ArrayList<String>()
            if (cfg.ultra) keep += "localStorage.setItem('cfg-ultra','1')"
            if (cfg.economy) keep += "localStorage.setItem('cfg-economia','1')"
            prefsScript = WebViewCompat.addDocumentStartJavaScript(v,
                "if(window.top===window&&/(^|\\.)pokeidle\\.io$/.test(location.hostname)){window.__pdMuted=${cfg.muted};try{${keep.joinToString(";")}}catch(e){}}",
                ORIGINS)
            ultraOn = cfg.ultra
            economyOn = cfg.economy
        }
        if (!firstTime && cfg.muted != mutedOn) v.evaluateJavascript("window.__pdMute&&window.__pdMute(${cfg.muted})", null)
        mutedOn = cfg.muted
        if (changed) {
            Log.write(index, "ULTRA ${if (cfg.ultra) "ligado" else "desligado"}, Economia ${if (cfg.economy) "ligada" else "desligada"}: recarregando o jogo")
            reload()
        }
    }

    // ---------------- lives ----------------

    fun twitchLogged() = Web.cookieMap(profile, "https://www.twitch.tv")["auth-token"].isNullOrEmpty().not()
    fun kickLogged() = Web.cookieMap(profile, "https://kick.com")["session_token"].isNullOrEmpty().not()

    fun twitchStatus(ch: String) = tw?.status(ch) ?: ""
    fun kickStatus(ch: String) = kw?.status(ch) ?: ""

    private fun pick(channels: List<String>, live: Map<String, Boolean?>, slots: Int) =
        channels.filter { live[it] != false }.take(slots) // sem resposta conta como no ar

    /** Depois de cada conferência de quem está no ar: decide as lives desta conta e liga os watchers. */
    suspend fun updateLives(twitchLive: Map<String, Boolean?>, kickLive: Map<String, Boolean?>) {
        val scope = scope
        if (game == null || scope == null || !cfg.on || !cfg.lives) {
            stopLives()
            return
        }
        val t = Web.cookieMap(profile, "https://www.twitch.tv")
        val token = t["auth-token"]?.ifEmpty { null }
        val kick = Web.cookieMap(profile, "https://kick.com")["session_token"]?.ifEmpty { null }?.let { Web.decode(it) }

        if (token != twitchToken) {
            tw?.stop()
            ps?.stop()
            tw = null
            ps = null
            twitchToken = token
            twitchDevice = t["unique_id"]?.ifEmpty { null }
            if (token != null) {
                val user = TwitchApi.userId(token, twitchDevice)
                if (user == null) {
                    Log.write(index, "Twitch: não consegui ler a conta logada (login expirado?)")
                    twitchToken = null // tenta de novo na próxima conferência
                } else {
                    tw = TwitchWatcher(user, token, twitchDevice, scope)
                    ps = TwitchPubSub(token, user, scope) { onTwitchNotice(it) }
                    Log.write(index, "Twitch: logado, assistindo sem player")
                }
            }
        }
        twitchOpen = if (tw == null) emptyList() else pick(Deck.config.twitchChannels, twitchLive, Deck.config.twitchLives)
        tw?.setChannels(twitchOpen)

        if (kick != kickToken) {
            kw?.stop()
            kw = null
            kickToken = kick
            if (kick != null) {
                kw = KickWatcher(kick, scope)
                Log.write(index, "Kick: logado, assistindo sem player")
            }
        }
        kickOpen = if (kw == null) emptyList() else pick(Deck.config.kickChannels, kickLive, Deck.config.kickLives)
        kw?.setChannels(kickOpen)
    }

    private fun stopLives() {
        tw?.stop()
        kw?.stop()
        ps?.stop()
        tw = null
        kw = null
        ps = null
        twitchToken = null
        kickToken = null
        twitchOpen = emptyList()
        kickOpen = emptyList()
    }

    private fun agent(): AgentPage = agent ?: AgentPage(Deck.app, profile) { agent = null }.also { agent = it }

    /** A página de pontos some depois de 10 min sem uso (no celular cada navegador escondido pesa). */
    private fun dropIdleAgent() {
        val a = agent ?: return
        if (!busyTwitch && !busyKick && System.currentTimeMillis() - a.usedAt > AGENT_IDLE_MS) {
            a.destroy()
            agent = null
        }
    }

    private fun onTwitchNotice(n: TwitchPubSub.Notice) {
        if (n.type != "claim-available" || !Deck.config.claimChest || twitchToken == null) return
        val channel = n.channelId?.let { twitchIds[it] }
        // O baú espera um clique: pega depois de uns segundos, como alguém que viu o aviso.
        scope?.launch {
            delay(Jitter.between(3_000, 20_000))
            for (ch in if (channel == null) twitchOpen.toList() else listOf(channel))
                twitchRun(ch, kind = null, spend = false, claimId = if (channel == null) null else n.claimId)
        }
    }

    private suspend fun twitchRun(ch: String, kind: String?, spend: Boolean, claimId: String? = null, claim: Boolean = true): Boolean {
        val token = twitchToken ?: return false
        val script = Points.twitchScript(ch, token, twitchDevice, kind,
            claim = claim && Deck.config.claimChest && (claimId != null || tp.canClaim(ch)),
            spend = spend && kind != null && tp.canSpend(ch), refund = tp.refundAt(ch), claimId = claimId)
        val json = agent().eval(TwitchApi.playerUrl(ch), script) ?: return false
        try {
            JSONObject(json).optString("channelId").takeIf { it.isNotEmpty() }?.let { twitchIds[it] = ch }
        } catch (_: JSONException) {
        }
        val notes = tp.apply(ch, json)
        notes.forEach { (what, text) -> Log.redeem(index, "Twitch", ch, what, text) }
        return notes.any { it.first == "resgate" }
    }

    private suspend fun kickRun(ch: String, kind: String?, spend: Boolean): Boolean {
        val token = kickToken ?: return false
        val script = Points.kickScript(ch, token, kind, spend && kind != null && kp.canSpend(ch), kp.refundAt(ch))
        val json = agent().eval(KickApi.playerUrl(ch), script) ?: return false
        val notes = kp.apply(ch, json)
        notes.forEach { (what, text) -> Log.redeem(index, "Kick", ch, what, text) }
        return notes.any { it.first == "resgate" }
    }

    private suspend fun breather() = delay(Jitter.between(2_000, 6_000))

    /** Saldo, baú e resgate automático em cada live aberta da Twitch. */
    private suspend fun twitchPoints() {
        if (busyTwitch || twitchOpen.isEmpty()) return
        busyTwitch = true
        try {
            for (ch in twitchOpen.toList()) {
                twitchRun(ch, cfg.redeemTwitch, spend = maySpend("twitch"))
                breather()
            }
        } finally {
            busyTwitch = false
        }
    }

    private suspend fun kickPoints() {
        if (busyKick || kickOpen.isEmpty()) return
        busyKick = true
        try {
            for (ch in kickOpen.toList()) {
                kickRun(ch, cfg.redeemKick, spend = maySpend("kick"))
                breather()
            }
        } finally {
            busyKick = false
        }
    }

    /**
     * Guarda do bônus: o bônus escolhido do site vence em menos de X min → lê o saldo dos canais fechados (lista +
     * oficiais do jogo), resgata 1 no melhor e confere no jogo se o tempo subiu; se não subir em 5 min, pausa 1 h.
     */
    private suspend fun guard(site: String) {
        val twitch = site == "twitch"
        val kind = (if (twitch) cfg.redeemTwitch else cfg.redeemKick) ?: return
        val bonus = (if (twitch) state?.twitch else state?.kick) ?: return
        val guard = if (twitch) tg else kg
        if (!cfg.guard || !cfg.lives || !bonus.linked || (if (twitch) twitchToken else kickToken) == null || (if (twitch) busyTwitch else busyKick)) return
        val now = System.currentTimeMillis()
        val left = bonus.left[kind] ?: 0
        guard.redeemedAt?.let { at ->
            if (left > guard.leftBefore + 60) {
                Log.redeem(index, siteName(site), "—", "guarda", "o bônus $kind subiu no jogo")
                guard.redeemedAt = null
            } else if (now - at > 5 * 60_000) {
                Log.redeem(index, siteName(site), "—", "guarda", "o bônus não subiu no jogo (site vinculado?); pausa de 1 h")
                guard.redeemedAt = null
                guard.holdUntil = now + 3600_000
            }
            return
        }
        if (now < guard.holdUntil) return
        if (left > Timing.GUARD_THRESHOLD_MIN * 60) {
            guard.noneNoted = false
            return
        }
        val candidates = Guard.candidates(if (twitch) Deck.config.twitchChannels else Deck.config.kickChannels,
            bonus.channels.map { it.first }, if (twitch) twitchOpen else kickOpen)
        if (candidates.isEmpty()) return
        if (twitch) busyTwitch = true else busyKick = true
        try {
            for (ch in candidates.filter { c -> guard.reads[c.lowercase()]?.let { now - it.at > 30 * 60_000 } ?: true }) {
                val script = if (twitch) Points.twitchScript(ch, twitchToken ?: return, twitchDevice, kind, claim = false, spend = false, refund = null)
                else Points.kickScript(ch, kickToken ?: return, kind, spend = false, refund = null)
                agent().eval(if (twitch) TwitchApi.playerUrl(ch) else KickApi.playerUrl(ch), script)?.let { guard.remember(ch, it, System.currentTimeMillis()) }
                breather()
            }
            val live = bonus.channels.filter { it.second }.map { it.first.lowercase() }.toSet()
            val pick = guard.pick(candidates, live)
            if (pick == null) {
                if (!guard.noneNoted) Log.redeem(index, siteName(site), "—", "guarda", "o bônus $kind vence em ${left / 60} min e nenhum canal tem os pontos")
                guard.noneNoted = true
                return
            }
            if (!maySpend(site)) return
            Log.redeem(index, siteName(site), pick, "guarda", "o bônus $kind vence em ${left / 60} min; resgatando aqui")
            val ok = if (twitch) twitchRun(pick, kind, spend = true, claim = false) else kickRun(pick, kind, spend = true)
            guard.reads.remove(pick.lowercase())
            if (ok) {
                guard.redeemedAt = System.currentTimeMillis()
                guard.leftBefore = left
            }
        } finally {
            if (twitch) busyTwitch = false else busyKick = false
        }
    }

    // ---------------- horário de bloqueio ----------------

    private fun redeemOn(site: String) = if (site == "twitch") cfg.redeemTwitchOn else cfg.redeemKickOn

    /** Segundos que faltam do buff escolhido para o site, como o jogo conta; null = não dá para saber agora. */
    fun buffLeft(site: String): Int? {
        val kind = (if (site == "twitch") cfg.redeemTwitch else cfg.redeemKick) ?: return null
        val bonus = (if (site == "twitch") state?.twitch else state?.kick) ?: return null
        return bonus.left[kind] ?: 0
    }

    /** O buff já passa do horário de bloqueio (a tela avisa antes de religar o resgate). */
    fun covered(site: String): Boolean {
        val left = buffLeft(site) ?: return false
        return RedeemBlock.covers(Deck.config.redeemBlockAt, left, LocalDateTime.now())
    }

    /**
     * Pode gastar pontos no prêmio do site agora? Com o resgate ligado, sim, a não ser que o buff que a conta já tem
     * passe do horário de bloqueio: aí desliga o resgate do site (até você religar) e registra.
     */
    private fun maySpend(site: String): Boolean {
        if (!redeemOn(site)) return false
        val now = LocalDateTime.now()
        val block = Deck.config.redeemBlockAt
        if (RedeemBlock.next(block, now) == null || blockIgnoredUntil[site]?.let { now < it } == true) return true
        val left = buffLeft(site) ?: return false // sem o tempo do buff no jogo não dá para conferir: espera a próxima volta
        if (!RedeemBlock.covers(block, left, now)) return true
        if (site == "twitch") cfg.redeemTwitchOn = false else cfg.redeemKickOn = false
        redeemSeen[site] = false
        val kind = if (site == "twitch") cfg.redeemTwitch else cfg.redeemKick
        Log.redeem(index, siteName(site), "—", "bloqueio",
            "o bônus $kind dura até ${now.plusSeconds(left.toLong()).format(ddhhmm)}, além do bloqueio das $block: resgate desligado")
        Deck.saveConfig()
        return false
    }
}
