package io.github.pedroaugustoptc.pokedeck.core

import org.json.JSONObject

/** O retrato da conta que o game-bridge.js manda (remontado dos deltas do socket do jogo). Ids de item são texto. */
class GameState(
    val connected: Boolean,
    val nick: String?,
    val gold: Long,
    val vip: Boolean,
    val balls: Map<String, Long>,
    val items: Map<String, Long>,
    val automation: JSONObject?,
    val maxHp: Double?,
    val ballCatalog: List<BallInfo>,
    val shop: List<ShopItem>,
    val shopLoaded: Boolean,
    val twitch: BonusSite?,
    val kick: BonusSite?,
) {
    fun have(id: String) = items[id] ?: 0L

    companion object {
        private fun counts(o: JSONObject?): Map<String, Long> =
            o?.keys()?.asSequence()?.associateWith { o.optLong(it, 0) } ?: emptyMap()

        private fun JSONObject.str(name: String): String? = if (!has(name) || isNull(name)) null else opt(name)?.toString()

        fun parse(m: JSONObject): GameState {
            val balls = m.optJSONArray("ballCatalog")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                    BallInfo(it.optInt("id"), it.str("nome") ?: "?", it.optDouble("priceGold", 0.0).toLong(), it.optBoolean("compravel"))
                }
            } ?: emptyList()
            val shop = m.optJSONArray("shop")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { i ->
                    val id = i.str("id") ?: return@mapNotNull null
                    ShopItem(id, i.str("name") ?: id, i.str("category") ?: "", i.optDouble("npcPrice", 0.0),
                        i.optDouble("healAmount", 0.0), i.optDouble("healPct", 0.0))
                }
            } ?: emptyList()
            val hp = m.optDouble("maxHp", 0.0)
            return GameState(
                connected = m.optBoolean("connected"),
                nick = m.str("nick"),
                gold = m.optDouble("gold", 0.0).toLong(),
                vip = m.optBoolean("vip"),
                balls = counts(m.optJSONObject("balls")),
                items = counts(m.optJSONObject("items")),
                automation = m.optJSONObject("automation"),
                maxHp = if (hp > 0) hp else null,
                ballCatalog = balls,
                shop = shop,
                shopLoaded = m.optBoolean("shopLoaded"),
                twitch = m.optJSONObject("twitch")?.let { BonusSite.parse(it) },
                kick = m.optJSONObject("kick")?.let { BonusSite.parse(it) },
            )
        }
    }
}

data class BallInfo(val id: Int, val name: String, val price: Long, val buyable: Boolean)

data class ShopItem(val id: String, val name: String, val category: String, val price: Double, val heal: Double, val healPct: Double)

/** O bônus das lives num site, como o jogo mostra: segundos que faltam por bônus ("xp", "captura", "shiny"). */
class BonusSite(val linked: Boolean, val left: Map<String, Int>, val channels: List<Pair<String, Boolean>>) {
    companion object {
        fun parse(e: JSONObject): BonusSite {
            val l = e.optJSONObject("left")
            val left = l?.keys()?.asSequence()?.associateWith { l.optInt(it, 0) } ?: emptyMap()
            val channels = e.optJSONArray("channels")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { ch ->
                    ch.optString("login").takeIf { it.isNotEmpty() }?.let { it.lowercase() to ch.optBoolean("live") }
                }
            } ?: emptyList()
            return BonusSite(e.optBoolean("linked"), left, channels)
        }
    }
}
