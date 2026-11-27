package io.github.pedroaugustoptc.pokedeck.net

import io.github.pedroaugustoptc.pokedeck.core.Jitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object KickApi {
    const val BASE = "https://kick.com"

    // O token público do player para o websocket de espectador (o mesmo do PC).
    const val CLIENT_TOKEN = "e1393935a959b4020a4491574f6490129f678acdaa92760471263db43487f823"

    fun playerUrl(slug: String) = "https://player.kick.com/${TwitchApi.enc(slug)}?autoplay=false&muted=true"

    fun request(url: String, token: String? = null): Request.Builder {
        val r = Http.get(url).header("Accept", "application/json").header("x-app-platform", "web").header("Referer", BASE)
        token?.let { r.header("Authorization", "Bearer $it") }
        return r
    }

    /** true no ar, false offline, null sem resposta. */
    suspend fun isLive(slug: String): Boolean? = try {
        val res = Http.send(request("$BASE/api/v2/channels/${TwitchApi.enc(slug)}"))
        when {
            res.code == 404 -> false
            res.ok -> parseLive(res.body)
            else -> null
        }
    } catch (_: IOException) {
        null
    }

    fun parseLive(json: String): Boolean? = try {
        val root = JSONObject(json)
        if (!root.has("livestream")) null
        else root.optJSONObject("livestream")?.let { it.opt("is_live") != false } ?: false
    } catch (_: JSONException) {
        null
    }
}

/**
 * Assiste às lives da Kick sem player (porte do KickWatcher do PC): token de espectador com o login, websocket de
 * espectador por live, "channel_handshake" ~15 s, "ping" ~30 s e "tracking.user.watch.livestream" no intervalo que a
 * Kick pede (~120 s). Todos com jitter.
 */
class KickWatcher(val token: String, private val scope: CoroutineScope) {
    private class Live(val slug: String) {
        var job: Job? = null
        var channelId: Any? = null
        var livestreamId: Any? = null
        var vodId: Any? = null
        var offline = false
        var watchedAt: LocalTime? = null
        var error: String? = null
    }

    private val lives = LinkedHashMap<String, Live>()
    private var asked: Long? = null
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    val channels: Set<String> get() = lives.keys

    fun setChannels(slugs: List<String>) {
        val next = slugs.toSet()
        for (gone in lives.keys.filter { it !in next }) lives.remove(gone)?.job?.cancel()
        for (slug in slugs) if (slug !in lives) Live(slug).also { lives[slug] = it; it.job = scope.launch { run(it) } }
    }

    fun status(slug: String): String {
        val l = lives[slug] ?: return ""
        return l.error ?: if (l.offline) "offline" else l.watchedAt?.let { "✓ ${it.format(hhmm)}" } ?: "…"
    }

    fun stop() {
        lives.values.forEach { it.job?.cancel() }
        lives.clear()
    }

    private fun j(seconds: Long) = Jitter.sec(seconds)

    private suspend fun run(live: Live) {
        var failures = 0
        while (true) {
            var ws: Ws? = null
            try {
                info(live)
                ws = Ws.connect("wss://websockets.kick.com/viewer/v1/connect?token=${TwitchApi.enc(viewerToken())}", "Origin" to KickApi.BASE)
                live.error = null
                failures = 0
                val every = askedInterval()
                var now = System.currentTimeMillis()
                var handshakeAt = now
                var watchAt = now + j(10)
                var pingAt = now + j(30)
                var infoAt = now + j(120)
                while (!ws.closed.isCompleted) {
                    now = System.currentTimeMillis()
                    while (ws.incoming.tryReceive().isSuccess) Unit // só esvazia: as respostas não importam
                    if (now >= infoAt) {
                        infoAt = now + j(120)
                        try {
                            info(live)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                    val cid = live.channelId
                    if (now >= handshakeAt && cid != null) {
                        handshakeAt = now + j(15)
                        ws.send(message("channel_handshake", JSONObject().put("channelId", cid.toString())))
                    }
                    if (now >= pingAt) {
                        pingAt = now + j(30)
                        ws.send("{\"type\":\"ping\"}")
                    }
                    if (now >= watchAt && !live.offline && cid != null && live.livestreamId != null && live.vodId != null) {
                        watchAt = now + Jitter.of(every, 0.1)
                        ws.send(message("user_event", JSONObject().put("name", "tracking.user.watch.livestream")
                            .put("channel_id", cid).put("livestream_id", live.livestreamId).put("vod_id", live.vodId)))
                        live.watchedAt = LocalTime.now()
                    }
                    delay(1_000)
                }
                live.error = "a Kick fechou a conexão"
            } catch (e: CancellationException) {
                live.channelId?.let { ws?.send(message("channel_disconnect", JSONObject().put("channelId", it.toString()))) }
                ws?.close()
                throw e
            } catch (e: Exception) {
                live.error = if (e is IOException) "a Kick não respondeu" else e.message
            }
            ws?.abort()
            failures++
            delay(j(minOf(120L, 2L shl minOf(failures, 6))))
        }
    }

    private fun message(type: String, message: JSONObject) =
        JSONObject().put("type", type).put("data", JSONObject().put("message", message)).toString()

    private suspend fun info(live: Live) {
        val res = Http.send(KickApi.request("${KickApi.BASE}/api/v2/channels/${TwitchApi.enc(live.slug)}/info", token))
        if (!res.ok) throw IllegalStateException("canal: erro ${res.code}")
        var root = JSONObject(res.body)
        if (root.optJSONObject("data") != null && !root.has("id")) root = root.getJSONObject("data")
        live.channelId = root.opt("id") ?: throw IllegalStateException("canal sem id")
        val stream = root.optJSONObject("livestream")
        live.offline = stream == null || stream.opt("is_live") == false
        live.livestreamId = stream?.opt("id")
        live.vodId = stream?.opt("vod_id")?.takeIf { it != JSONObject.NULL }
    }

    private suspend fun viewerToken(): String {
        val res = Http.send(KickApi.request("https://websockets.kick.com/viewer/v1/token", token).header("X-CLIENT-TOKEN", KickApi.CLIENT_TOKEN))
        if (!res.ok) throw IllegalStateException("token de espectador: erro ${res.code}")
        return JSONObject(res.body).optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("sem token de espectador")
    }

    private suspend fun askedInterval(): Long {
        asked?.let { return it }
        var ms = 120_000L
        try {
            val res = Http.send(KickApi.request("${KickApi.BASE}/api/internal/settings/global", token))
            if (res.ok) {
                val root = JSONObject(res.body)
                val events = root.optJSONObject("events") ?: root.optJSONObject("data")?.optJSONObject("events")
                val i = events?.optJSONObject("tracking.user.watch.livestream")?.opt("interval")
                if (i is Number) ms = (i.toDouble().coerceIn(30.0, 600.0) * 1000).toLong()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        asked = ms
        return ms
    }
}
