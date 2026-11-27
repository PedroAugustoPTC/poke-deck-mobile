package io.github.pedroaugustoptc.pokedeck.net

import io.github.pedroaugustoptc.pokedeck.core.Jitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/** A API do site da Twitch (gql.twitch.tv com o Client-Id público do site), como o PC usa. */
object TwitchApi {
    const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    const val GQL_BASE = "https://gql.twitch.tv"
    const val GQL = "$GQL_BASE/gql"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun playerUrl(channel: String) =
        "https://player.twitch.tv/?channel=${enc(channel)}&parent=twitch.tv&player=popout&autoplay=false&muted=true"

    /** O "data" da resposta; null se falhou. */
    suspend fun query(body: JSONObject, token: String? = null, device: String? = null): JSONObject? {
        val req = Http.get(GQL).post(body.toString().toRequestBody("text/plain".toMediaType())).header("Client-Id", CLIENT_ID)
        token?.let { req.header("Authorization", "OAuth $it") }
        device?.let { req.header("X-Device-Id", it) }
        val res = Http.send(req)
        if (!res.ok) return null
        return try {
            JSONObject(res.body).optJSONObject("data")
        } catch (_: JSONException) {
            null
        }
    }

    /** Cada canal: true no ar, false offline/inexistente, null sem resposta. Uma consulta para todos. */
    suspend fun live(channels: List<String>): Map<String, Boolean?> {
        if (channels.isEmpty()) return emptyMap()
        return try {
            val q = JSONObject().put("query", "query(\$l:[String!]){users(logins:\$l){login stream{id}}}")
                .put("variables", JSONObject().put("l", JSONArray(channels)))
            query(q)?.let { parseLive(channels, it) } ?: channels.associateWith { null }
        } catch (_: IOException) {
            channels.associateWith { null }
        }
    }

    fun parseLive(channels: List<String>, data: JSONObject): Map<String, Boolean?> {
        val users = data.optJSONArray("users") ?: return channels.associateWith { null }
        val found = HashMap<String, Boolean>()
        for (i in 0 until users.length()) {
            val u = users.optJSONObject(i) ?: continue
            val name = u.optString("login").takeIf { it.isNotEmpty() } ?: continue
            found[name.lowercase()] = u.optJSONObject("stream") != null
        }
        return channels.associateWith { found[it.lowercase()] ?: false }
    }

    /** O id da conta logada (o cookie auth-token). */
    suspend fun userId(token: String, device: String?): String? = try {
        query(JSONObject().put("query", "query{currentUser{id}}"), token, device)?.optJSONObject("currentUser")?.optString("id")?.takeIf { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    }
}

/**
 * Assiste às lives da Twitch sem player: o aviso "minute-watched" a cada ~minuto (o que o player manda) e, como um
 * player, a lista de pedaços na menor qualidade com só o HEAD de cada pedaço novo. Porte do TwitchWatcher do PC.
 * Roda no escopo da conta (thread principal); a rede vai para fora pelo [Http].
 */
class TwitchWatcher(private val userId: String, val token: String, private val device: String?, scope: CoroutineScope) {
    companion object {
        private const val PLAYBACK_HASH = "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9"
        private val SETTINGS_JS = Regex("(https?://[^\"'\\s]+/config/settings[^\"'\\s]*?\\.js)")
        private val SPADE = Regex("\"spade_url\"\\s*:\\s*\"(.*?)\"")
        private val BANDWIDTH = Regex("(?<![-A-Z])BANDWIDTH=(\\d+)")
        private var spade: Pair<String, Long>? = null
        private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

        /** A variante de menor banda que tem vídeo (RESOLUTION=), ou a última se nenhuma tiver. */
        fun lowestVariant(master: String, playlist: String): String? {
            val lines = playlist.replace("\r\n", "\n").split('\n')
            val variants = ArrayList<Triple<Long, Boolean, String>>()
            var i = 0
            while (i < lines.size) {
                if (lines[i].startsWith("#EXT-X-STREAM-INF:")) {
                    var next = i + 1
                    while (next < lines.size && (lines[next].isEmpty() || lines[next].startsWith("#"))) next++
                    if (next >= lines.size) break
                    val bw = BANDWIDTH.find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
                    variants += Triple(bw, "RESOLUTION=" in lines[i], resolve(master, lines[next]))
                    i = next
                }
                i++
            }
            return variants.filter { it.second }.minByOrNull { it.first }?.third ?: variants.lastOrNull()?.third
        }

        private fun resolve(base: String, ref: String) = base.toHttpUrl().resolve(ref)?.toString() ?: ref
    }

    private class Live(val login: String) {
        var channelId: String? = null
        var broadcastId: String? = null
        var game: String? = null
        var gameId: String? = null
        var mediaUrl: String? = null
        var error: String? = null
        var offline = false
        var infoAt = 0L
        var minuteAt = System.currentTimeMillis()
        var playlistAt = 0L
        var mediaAt = 0L
        var watchedAt: LocalTime? = null
        val seen = LinkedHashSet<String>()
    }

    private val lives = LinkedHashMap<String, Live>()
    private val job: Job = scope.launch { run() }

    val channels: Set<String> get() = lives.keys

    fun setChannels(logins: List<String>) {
        lives.keys.retainAll(logins.toSet())
        for (l in logins) lives.getOrPut(l) { Live(l) }
    }

    fun status(login: String): String {
        val l = lives[login] ?: return ""
        return l.error ?: l.watchedAt?.let { "✓ ${it.format(hhmm)}" } ?: "…"
    }

    fun stop() = job.cancel()

    private suspend fun run() {
        while (true) {
            for (live in lives.values.toList()) {
                try {
                    step(live)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    live.error = if (e is IOException) "a Twitch não respondeu" else e.message
                }
            }
            delay(Jitter.of(5_000, 0.4))
        }
    }

    private suspend fun step(live: Live) {
        val now = System.currentTimeMillis()
        if (live.channelId == null || now >= live.infoAt) info(live)
        if (live.offline || live.broadcastId == null) return
        if (now >= live.playlistAt) {
            live.playlistAt = now + Jitter.sec(20)
            segments(live)
        }
        if (now >= live.minuteAt) {
            live.minuteAt = now + Jitter.of(60_000, 0.1) // o player manda ~1/min
            minute(live)
        }
    }

    private suspend fun info(live: Live) {
        live.infoAt = System.currentTimeMillis() + Jitter.sec(120)
        val q = JSONObject().put("query", "query(\$l:String!){user(login:\$l){id stream{id game{id name}}}}")
            .put("variables", JSONObject().put("l", live.login))
        val user = TwitchApi.query(q)?.optJSONObject("user")
        if (user == null) {
            live.error = "canal não encontrado"
            return
        }
        live.channelId = user.optString("id")
        val stream = user.optJSONObject("stream")
        if (stream == null) {
            live.offline = true
            live.error = "offline"
            return
        }
        val broadcast = stream.optString("id")
        if (broadcast != live.broadcastId) {
            live.mediaUrl = null
            live.seen.clear()
        }
        live.broadcastId = broadcast
        live.offline = false
        stream.optJSONObject("game")?.let { live.game = it.optString("name"); live.gameId = it.optString("id") }
        if (live.error == "offline") live.error = null
    }

    private suspend fun minute(live: Live) {
        val url = spadeUrl(live.login)
        if (url == null) {
            live.error = "sem o endereço do minute-watched"
            return
        }
        val props = JSONObject().put("channel_id", live.channelId).put("broadcast_id", live.broadcastId).put("player", "site")
            .put("user_id", userId).put("live", true).put("channel", live.login)
        if (live.game != null && live.gameId != null) props.put("game", live.game).put("game_id", live.gameId)
        val json = JSONArray().put(JSONObject().put("event", "minute-watched").put("properties", props)).toString()
        val body = FormBody.Builder().add("data", Base64.getEncoder().encodeToString(json.toByteArray())).build()
        val res = Http.send(Http.get(url).post(body))
        if (res.ok) {
            live.watchedAt = LocalTime.now()
            live.error = null
        } else {
            live.error = "minute-watched: erro ${res.code}"
            spade = null
        }
    }

    private suspend fun spadeUrl(login: String): String? {
        spade?.let { (url, at) -> if (System.currentTimeMillis() - at < 6 * 3600_000L) return url }
        val page = Http.send(Http.get("https://www.twitch.tv/${TwitchApi.enc(login)}"))
        val settings = SETTINGS_JS.find(page.body)?.groupValues?.get(1) ?: return null
        val cfg = Http.send(Http.get(settings))
        val raw = SPADE.find(cfg.body)?.groupValues?.get(1) ?: return null
        val url = try {
            JSONArray("[\"$raw\"]").getString(0) // desfaz os escapes \u002F do JS
        } catch (_: JSONException) {
            raw
        }
        spade = url to System.currentTimeMillis()
        return url
    }

    private suspend fun segments(live: Live) {
        if (live.mediaUrl == null || System.currentTimeMillis() - live.mediaAt > 10 * 60_000) {
            live.mediaUrl = mediaUrl(live)
            live.mediaAt = System.currentTimeMillis()
        }
        val media = live.mediaUrl ?: return
        val res = Http.send(Http.get(media))
        if (!res.ok) {
            live.mediaUrl = null
            return
        }
        for (line in res.body.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val seg = resolve(media, line)
            if (!live.seen.add(seg)) continue
            while (live.seen.size > 300) live.seen.remove(live.seen.first())
            Http.send(Http.get(seg).head(), readBody = false)
        }
    }

    private suspend fun mediaUrl(live: Live): String? {
        val body = JSONObject().put("operationName", "PlaybackAccessToken")
            .put("variables", JSONObject().put("login", live.login).put("isLive", true).put("isVod", false).put("vodID", "")
                .put("playerType", "site").put("platform", "web"))
            .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", PLAYBACK_HASH)))
        var token: JSONObject? = null
        for (withLogin in listOf(true, false)) {
            token = TwitchApi.query(body, if (withLogin) this.token else null, if (withLogin) device else null)?.optJSONObject("streamPlaybackAccessToken")
            if (token != null) break
        }
        token ?: return null
        val master = "https://usher.ttvnw.net/api/channel/hls/${TwitchApi.enc(live.login)}.m3u8" +
            "?sig=${TwitchApi.enc(token.optString("signature"))}&token=${TwitchApi.enc(token.optString("value"))}" +
            "&allow_source=true&allow_audio_only=true&player=twitchweb&type=any&p=${Random.nextInt(1_000_000, 9_999_999)}"
        val res = Http.send(Http.get(master))
        return if (res.ok) lowestVariant(master, res.body) else null
    }
}

/**
 * Os avisos da Twitch da conta (PubSub, tópico community-points-user-v1.<id>): o baú que apareceu (claim-available).
 * Reconecta sozinho (5 s … 2 min); ERR_BADAUTH para de vez.
 */
class TwitchPubSub(private val token: String, private val userId: String, scope: CoroutineScope, private val notice: (Notice) -> Unit) {
    data class Notice(val type: String, val channelId: String?, val claimId: String?)

    var error: String? = null
        private set
    private val job = scope.launch { run() }

    fun stop() = job.cancel()

    private suspend fun run() {
        var wait = 5_000L
        while (true) {
            var quit = false
            var ws: Ws? = null
            try {
                ws = Ws.connect("wss://pubsub-edge.twitch.tv/v1")
                error = null
                wait = 5_000
                ws.send(JSONObject().put("type", "LISTEN").put("nonce", UUID.randomUUID().toString().replace("-", ""))
                    .put("data", JSONObject().put("topics", JSONArray().put("community-points-user-v1.$userId")).put("auth_token", token)).toString())
                quit = receive(ws)
            } catch (e: CancellationException) {
                ws?.abort()
                throw e
            } catch (e: Exception) {
                error = e.message
            }
            ws?.abort()
            if (quit) return
            delay(Jitter.of(wait, 0.3))
            wait = minOf(wait * 2, 120_000)
        }
    }

    /** true: parar; false: reconectar. Manda PING ~1/min e derruba se o PONG não vier em 15 s. */
    private suspend fun receive(ws: Ws): Boolean {
        var pingAt = System.currentTimeMillis() + Jitter.of(60_000, 0.2)
        var pongDue = Long.MAX_VALUE
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (now >= pongDue) return false
            if (now >= pingAt) {
                ws.send("{\"type\":\"PING\"}")
                pingAt = now + Jitter.of(60_000, 0.2)
                pongDue = now + 15_000
            }
            val got = withTimeoutOrNull(1_000) { ws.incoming.receiveCatching() } ?: continue
            val msg = got.getOrNull() ?: return false // fechou
            when (handle(msg)) {
                true -> return true
                false -> return false
                null -> if (msg.contains("\"PONG\"")) pongDue = Long.MAX_VALUE
            }
        }
        return false
    }

    private fun handle(text: String): Boolean? {
        try {
            val root = JSONObject(text)
            when (root.optString("type")) {
                "RECONNECT" -> return false
                "RESPONSE" -> {
                    val err = root.optString("error")
                    if (err.isEmpty()) return null
                    error = if (err == "ERR_BADAUTH") "a Twitch recusou o login nos avisos" else err
                    return if (err == "ERR_BADAUTH") true else null
                }
                "MESSAGE" -> notice(parseNotice(JSONObject(root.getJSONObject("data").optString("message", "{}"))))
            }
        } catch (_: JSONException) {
        }
        return null
    }

    companion object {
        fun parseNotice(m: JSONObject): Notice {
            val type = m.optString("type")
            var channel: String? = null
            var claim: String? = null
            m.optJSONObject("data")?.let { d ->
                d.optJSONObject("claim")?.let { c ->
                    channel = c.optString("channel_id").ifEmpty { null }
                    claim = c.optString("id").ifEmpty { null }
                }
                if (channel == null) channel = d.optString("channel_id").ifEmpty { null }
                if (channel == null) channel = d.optJSONObject("balance")?.optString("channel_id")?.ifEmpty { null }
            }
            return Notice(type, channel, claim)
        }
    }
}
