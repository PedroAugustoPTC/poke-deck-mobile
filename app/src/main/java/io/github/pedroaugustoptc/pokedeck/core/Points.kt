package io.github.pedroaugustoptc.pokedeck.core

import io.github.pedroaugustoptc.pokedeck.net.KickApi
import io.github.pedroaugustoptc.pokedeck.net.TwitchApi
import org.json.JSONException
import org.json.JSONObject

/**
 * Pontos de canal de uma conta num site (porte do Points.cs): os scripts que rodam DENTRO da página real (player da
 * Twitch/Kick, com a checagem anti-robô e os cookies do próprio site) e o acompanhamento de cada canal (saldo, espera
 * após recusa, detecção de pontos devolvidos).
 */
class Points(val site: String, private val now: () -> Long = System::currentTimeMillis) {
    class Channel {
        var points: Int? = null
        var cost: Int? = null
        var paused = false
        var redeemedAt: Long? = null
        var retryAt = 0L
        var claimRetryAt = 0L
        var text = ""
    }

    private val channels = HashMap<String, Channel>()

    /** Por que parou de resgatar nesta conta (pontos devolvidos: site não vinculado no jogo). null: resgatando. */
    var stopped: String? = null
    var retryWaitMs = Timing.REDEEM_RETRY_MIN * 60_000

    fun channel(ch: String): Channel? = channels[ch.lowercase()]
    private fun of(ch: String) = channels.getOrPut(ch.lowercase()) { Channel() }

    fun canSpend(ch: String) = stopped == null && now() >= (channel(ch)?.retryAt ?: 0)
    fun canClaim(ch: String) = now() >= (channel(ch)?.claimRetryAt ?: 0)

    /** A partir desse saldo, os pontos do último resgate voltaram (não resgata em cima da devolução). */
    fun refundAt(ch: String): Int? {
        val c = channel(ch) ?: return null
        val p = c.points ?: return null
        val cost = c.cost ?: return null
        val at = c.redeemedAt ?: return null
        return if (now() - at < REFUND_WINDOW) p + cost / 2 else null
    }

    /** Lê o JSON que o script devolveu; devolve o que aconteceu (tipo, texto) para o registro dos resgates. */
    fun apply(ch: String, json: String): List<Pair<String, String>> {
        val notes = ArrayList<Pair<String, String>>()
        val c = of(ch)
        val now = now()
        val r = try {
            JSONObject(json)
        } catch (e: JSONException) {
            c.text = "resposta estranha da página"
            return notes
        }
        fun int(n: String): Int? = (r.opt(n) as? Number)?.toInt()
        fun str(n: String): String? = r.opt(n) as? String
        fun isTrue(n: String) = r.opt(n) == true

        var points = int("points")
        val refund = refundAt(ch)
        if (points != null && refund != null && points >= refund) {
            stopped = "os pontos do resgate voltaram (o site está vinculado no jogo?)"
            notes += "devolvido" to "$stopped; parei de resgatar nesta conta"
        }
        if (isTrue("claimed")) notes += "baú" to "pegou o baú (+50)"
        str("claimError")?.let {
            c.claimRetryAt = now + retryWaitMs
            notes += "baú recusado" to "baú recusado ($it)"
        }
        int("cost")?.let { c.cost = it }
        c.paused = isTrue("paused")
        val title = str("title") ?: "bônus"
        if (isTrue("redeemed")) {
            c.redeemedAt = now
            val k = c.cost
            if (points != null && k != null) points -= k
            notes += "resgate" to "resgatou $title" + (points?.let { " (saldo $it)" } ?: "")
        } else if (r.has("refused") || r.has("redeemed")) {
            c.retryAt = now + retryWaitMs
            notes += "recusado" to "resgate recusado (${str("refused") ?: "erro " + int("status")}); tento de novo em ${retryWaitMs / 60_000} min"
        }
        // Saldo que caiu sem resgate do app (descontado o baú): pontos gastos em outro lugar com a conta.
        val before = c.points
        if (points != null && before != null && !isTrue("redeemed") && points + 100 < before + (if (isTrue("claimed")) 50 else 0))
            notes += "gasto fora" to "o saldo caiu ${before - points} sem resgate do app (gasto em outro lugar?)"
        if (points != null) c.points = points
        val show = c.points
        c.text = str("error") ?: when {
            show == null -> ""
            c.cost != null -> "$show/${c.cost}${if (c.paused) " pausado" else ""}"
            else -> "$show"
        }
        return notes
    }

    companion object {
        const val REFUND_WINDOW = 30 * 60_000L

        /** A palavra do bônus no título da recompensa do canal. */
        fun word(kind: String) = when (kind) {
            "captura" -> "captur"
            "shiny" -> "lure|shiny"
            else -> "\\bxp\\b"
        }

        private fun nul(v: Any?) = v ?: JSONObject.NULL

        private const val TWITCH_GQL =
            " const base = { 'Client-Id': a.clientId, Authorization: 'OAuth ' + a.token, 'Content-Type': 'text/plain;charset=UTF-8'," +
                "  ...(a.device ? { 'X-Device-Id': a.device } : {}) };" +
                " let integrity = null;" +
                " const gql = async (query, variables) => {" +
                "  const body = JSON.stringify({ query, variables });" +
                "  for (let i = 0; i < 2; i++) {" +
                "   const r = await fetch(a.api + '/gql', { method: 'POST', cache: 'no-cache'," +
                "    headers: integrity ? { ...base, 'Client-Integrity': integrity } : base, body });" +
                "   if (!r.ok) return { failed: 'erro ' + r.status };" +
                "   const j = await r.json();" +
                "   const challenged = !!j?.extensions?.challenge || (j?.errors || []).some(e => e?.extensions?.code === 'IntegrityCheckFailed');" +
                "   if (!challenged) return j;" +
                "   if (integrity) return { failed: 'checagem anti-robô da Twitch' };" +
                "   const t = await fetch(a.api + '/integrity', { method: 'POST', cache: 'no-cache', headers: base });" +
                "   const tj = t.ok ? await t.json() : null;" +
                "   if (!tj?.token) return { failed: 'checagem anti-robô da Twitch (' + t.status + ')' };" +
                "   integrity = tj.token;" +
                "  }" +
                " };" +
                " const fail = (j, x) => j?.failed || x?.error?.code || j?.errors?.[0]?.message || 'sem resposta';"

        /**
         * Twitch: lê o saldo; com [claim], pega o baú (do availableClaim ou do id do aviso); com [kind] e [spend], resgata
         * 1 (a duração liberada mais barata). Devolve uma Promise de texto JSON.
         */
        fun twitchScript(channel: String, token: String, device: String?, kind: String?, claim: Boolean, spend: Boolean, refund: Int?, claimId: String? = null): String {
            val args = JSONObject()
                .put("api", TwitchApi.GQL_BASE).put("channel", channel).put("token", token).put("device", nul(device))
                .put("clientId", TwitchApi.CLIENT_ID).put("word", nul(kind?.let { word(it) })).put("spend", spend)
                .put("refund", nul(refund)).put("claim", claim).put("claimId", nul(claimId))
            return "(async a => {" + TWITCH_GQL +
                " try {" +
                "  const j = await gql('query(\$login:String!){currentUser{id} user(login:\$login){id channel{self{communityPoints{balance availableClaim{id}}}' +" +
                "   ' communityPointsSettings{customRewards{id title prompt cost isEnabled isPaused isInStock cooldownExpiresAt}}}}}', { login: a.channel });" +
                "  if (j?.failed) return { error: 'saldo: ' + j.failed };" +
                "  const user = j?.data?.user;" +
                "  if (!user?.channel) return { error: 'canal não encontrado' };" +
                "  const cp = user.channel.self?.communityPoints;" +
                "  if (typeof cp?.balance !== 'number') return { error: 'sem saldo (login da Twitch?)' };" +
                "  let points = cp.balance; const out = { points, me: j?.data?.currentUser?.id ?? null, channelId: user.id };" +
                "  const claimId = cp.availableClaim?.id || a.claimId;" +
                "  if (a.claim && claimId) {" +
                "   const k = await gql('mutation(\$i:ClaimCommunityPointsInput!){claimCommunityPoints(input:\$i){claim{id} currentPoints error{code}}}'," +
                "    { i: { channelID: user.id, claimID: claimId } });" +
                "   const p = k?.data?.claimCommunityPoints;" +
                "   if (p && !p.error && !k.errors) { out.claimed = true; points = typeof p.currentPoints === 'number' ? p.currentPoints : points + 50; }" +
                "   else out.claimError = fail(k, p);" +
                "   out.points = points;" +
                "  }" +
                "  if (!a.word) return out;" +
                "  const word = new RegExp(a.word, 'i');" +
                "  const all = (user.channel.communityPointsSettings?.customRewards || []).slice().sort((x, y) => x.cost - y.cost)" +
                "   .filter(x => x.isEnabled && word.test(x.title || '') && /pokeidle/i.test((x.title || '') + ' ' + (x.prompt || '')));" +
                "  const shut = x => x.isPaused || !x.isInStock || !!x.cooldownExpiresAt;" +
                "  const reward = all.find(x => !shut(x)) || all[0];" +
                "  if (!reward) return { ...out, error: 'canal sem o bônus' };" +
                "  Object.assign(out, { cost: reward.cost, title: reward.title, paused: shut(reward) });" +
                "  if (!a.spend || out.paused || points < reward.cost || (a.refund !== null && points >= a.refund)) return out;" +
                "  const id = [...crypto.getRandomValues(new Uint8Array(16))].map(b => b.toString(16).padStart(2, '0')).join('');" +
                "  const r = await gql('mutation(\$i:RedeemCommunityPointsCustomRewardInput!){redeemCommunityPointsCustomReward(input:\$i){redemption{id} error{code}}}'," +
                "   { i: { channelID: user.id, cost: reward.cost, prompt: reward.prompt ?? null, rewardID: reward.id, title: reward.title, transactionID: id } });" +
                "  const p = r?.data?.redeemCommunityPointsCustomReward;" +
                "  if (p && !p.error && !r.errors) return { ...out, redeemed: true };" +
                "  return { ...out, refused: fail(r, p) };" +
                " } catch (e) { return { error: 'sem resposta da Twitch: ' + (e && e.message || e) }; }" +
                "})($args).then(o => JSON.stringify(o))"
        }

        /** Kick: lê o saldo e, com [spend], resgata 1 (a duração liberada mais barata). */
        fun kickScript(channel: String, token: String, kind: String?, spend: Boolean, refund: Int?): String {
            val args = JSONObject().put("api", KickApi.BASE).put("channel", channel).put("token", token)
                .put("word", nul(kind?.let { word(it) })).put("spend", spend).put("refund", nul(refund))
            return "(async a => {" +
                " const call = (path, init) => fetch(a.api + '/api/v2/channels/' + encodeURIComponent(a.channel) + path," +
                "  { cache: 'no-cache', credentials: 'include', ...init, headers: { Accept: 'application/json', 'x-app-platform': 'web'," +
                "   Authorization: 'Bearer ' + a.token, ...(init && init.body ? { 'Content-Type': 'application/json' } : {}) } });" +
                " try {" +
                "  let r = await call('/points');" +
                "  if (!r.ok) return { error: 'saldo: erro ' + r.status };" +
                "  const points = (await r.json())?.data?.points;" +
                "  if (typeof points !== 'number') return { error: 'sem saldo (login da Kick?)' };" +
                "  if (!a.word) return { points };" +
                "  r = await call('/rewards');" +
                "  if (!r.ok) return { points, error: 'bônus: erro ' + r.status };" +
                "  const word = new RegExp(a.word, 'i');" +
                "  const all = ((await r.json())?.data || []).slice().sort((x, y) => x.cost - y.cost)" +
                "   .filter(x => x.is_enabled && word.test(x.title || '') && /pokeidle/i.test((x.title || '') + ' ' + (x.description || '')));" +
                "  const reward = all.find(x => !x.is_paused) || all[0];" +
                "  if (!reward) return { points, error: 'canal sem o bônus' };" +
                "  const out = { points, cost: reward.cost, title: reward.title, paused: !!reward.is_paused };" +
                "  if (!a.spend || reward.is_paused || points < reward.cost || (a.refund !== null && points >= a.refund)) return out;" +
                "  const abc = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'; let t = Date.now(), id = '';" +
                "  for (let i = 0; i < 10; i++) { id = abc[t % 32] + id; t = Math.floor(t / 32); }" +
                "  for (let i = 0; i < 16; i++) id += abc[Math.floor(Math.random() * 32)];" +
                "  r = await call('/rewards/' + reward.id + '/redeem', { method: 'POST', body: JSON.stringify({ transaction_id: id, message: '' }) });" +
                "  return { ...out, redeemed: r.ok, status: r.status };" +
                " } catch (e) { return { error: 'sem resposta da Kick: ' + (e && e.message || e) }; }" +
                "})($args).then(o => JSON.stringify(o))"
        }
    }
}

/**
 * A guarda do bônus numa conta e num site: quando o bônus escolhido vence em menos de X min, resgata 1 num canal com
 * pontos parados (fora os abertos, que o resgate automático já usa). Offline primeiro, depois o de mais pontos.
 */
class Guard {
    data class Read(val at: Long, val points: Int?, val cost: Int?, val paused: Boolean)

    var holdUntil = 0L
    var redeemedAt: Long? = null
    var leftBefore = 0
    var noneNoted = false
    val reads = HashMap<String, Read>()

    fun remember(channel: String, json: String, now: Long) {
        var points: Int? = null
        var cost: Int? = null
        var paused = false
        try {
            val r = JSONObject(json)
            points = (r.opt("points") as? Number)?.toInt()
            cost = (r.opt("cost") as? Number)?.toInt()
            paused = r.opt("paused") == true
        } catch (_: JSONException) {
        }
        reads[channel.lowercase()] = Read(now, points, cost, paused)
    }

    fun pick(candidates: List<String>, live: Set<String>): String? =
        candidates.filter { c ->
            val r = reads[c.lowercase()]
            r != null && r.points != null && r.cost != null && !r.paused && r.points >= r.cost
        }.sortedWith(compareBy<String> { it.lowercase() in live }.thenByDescending { reads[it.lowercase()]!!.points })
            .firstOrNull()

    companion object {
        fun candidates(configured: List<String>, official: List<String>, open: List<String>): List<String> {
            val skip = open.map { it.lowercase() }.toSet()
            return (configured + official).filter { it.isNotEmpty() && it.lowercase() !in skip }.distinctBy { it.lowercase() }
        }
    }
}
