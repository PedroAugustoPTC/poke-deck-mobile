package io.github.pedroaugustoptc.pokedeck.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime

/** Tudo o que o usuário define, salvo em filesDir/config.json (espelho do Config.cs do PC, sem layout/voto/extensões). */
class Config(
    var accounts: List<AccountConfig> = (1..MAX_ACCOUNTS).map { AccountConfig(name = "Conta $it", on = it == 1) },
    // Os streamers oficiais do painel de bônus do jogo, em ordem de prioridade (mesma lista do PC).
    var twitchChannels: List<String> = listOf(
        "tftoddy", "clovaobengala", "axtlol", "patopapao", "matta", "guaxilol1", "rodil",
        "cogu", "luccatv", "faasii", "biscoitao", "techzz", "dersin2k", "denissnidertv",
    ),
    var kickChannels: List<String> = listOf("biscoitao", "yetz", "surskity"),
    var twitchLives: Int = 2,
    var kickLives: Int = 2, // a Kick só conta pontos de 2 lives por conta
    var claimChest: Boolean = true,
    /** "HH:mm" de todas as contas, ou null: buff que já passa desse horário não resgata mais. */
    var redeemBlockAt: String? = null,
    /** Tour visto. true para quem já tinha config; só o primeiro acesso (sem arquivo) começa false. */
    var tourDone: Boolean = true,
    /** A tela "Deixar rodando" (permissões e bateria) já foi mostrada uma vez. */
    var setupDone: Boolean = true,
) {
    fun fix(): Config {
        val list = accounts.take(MAX_ACCOUNTS).toMutableList()
        while (list.size < MAX_ACCOUNTS) list += AccountConfig(name = "Conta ${list.size + 1}", on = false)
        accounts = list
        twitchChannels = clean(twitchChannels)
        kickChannels = clean(kickChannels)
        twitchLives = twitchLives.coerceIn(0, 5)
        kickLives = kickLives.coerceIn(0, 2)
        if (RedeemBlock.next(redeemBlockAt, LocalDateTime.now()) == null) redeemBlockAt = null
        return this
    }

    fun toJson(): JSONObject = JSONObject()
        .put("accounts", JSONArray(accounts.map { it.toJson() }))
        .put("twitchChannels", JSONArray(twitchChannels))
        .put("kickChannels", JSONArray(kickChannels))
        .put("twitchLives", twitchLives)
        .put("kickLives", kickLives)
        .put("claimChest", claimChest)
        .put("redeemBlockAt", redeemBlockAt ?: JSONObject.NULL)
        .put("tourDone", tourDone)
        .put("setupDone", setupDone)

    /** Cópia independente (a Config edita uma cópia; nada vale até Salvar). */
    fun copy(): Config = from(toJson())

    fun save(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(toJson().toString(1))
        if (!tmp.renameTo(file)) { // no Android o rename por cima funciona; o fallback cobre FS estranhos
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val MAX_ACCOUNTS = 4

        private fun clean(channels: List<String>) =
            channels.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

        fun load(file: File): Config {
            if (!file.exists()) return Config(tourDone = false, setupDone = false)
            return try {
                from(JSONObject(file.readText())).fix()
            } catch (e: JSONException) {
                file.copyTo(File(file.path + ".bad"), overwrite = true) // não perde o arquivo que não abriu
                Config()
            }
        }

        fun from(o: JSONObject): Config {
            val d = Config()
            return Config(
                accounts = o.optJSONArray("accounts")?.let { a -> (0 until a.length()).map { AccountConfig.from(a.optJSONObject(it) ?: JSONObject()) } } ?: d.accounts,
                twitchChannels = o.optJSONArray("twitchChannels")?.strings() ?: d.twitchChannels,
                kickChannels = o.optJSONArray("kickChannels")?.strings() ?: d.kickChannels,
                twitchLives = o.optInt("twitchLives", d.twitchLives),
                kickLives = o.optInt("kickLives", d.kickLives),
                claimChest = o.optBoolean("claimChest", d.claimChest),
                redeemBlockAt = o.optStr("redeemBlockAt"),
                tourDone = o.optBoolean("tourDone", true),
                setupDone = o.optBoolean("setupDone", true),
            )
        }
    }
}

class AccountConfig(
    var name: String = "",
    var on: Boolean = true,
    var ultra: Boolean = false,
    var economy: Boolean = false,
    var muted: Boolean = true,
    /** Esta conta assiste às lives (a conta do jogo vinculada à Twitch/Kick logada nela). */
    var lives: Boolean = false,
    /** O bônus resgatado com os pontos de cada site: null, "xp", "captura" ou "shiny". */
    var redeemTwitch: String? = null,
    var redeemKick: String? = null,
    var redeemTwitchOn: Boolean = true,
    var redeemKickOn: Boolean = true,
    /** Guarda do bônus: perto de vencer, resgata num canal com pontos parados. */
    var guard: Boolean = true,
    var restock: RestockConfig = RestockConfig(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("on", on).put("ultra", ultra).put("economy", economy).put("muted", muted)
        .put("lives", lives).put("redeemTwitch", redeemTwitch ?: JSONObject.NULL).put("redeemKick", redeemKick ?: JSONObject.NULL)
        .put("redeemTwitchOn", redeemTwitchOn).put("redeemKickOn", redeemKickOn).put("guard", guard)
        .put("restock", restock.toJson())

    companion object {
        val KINDS = listOf("xp", "captura", "shiny")

        fun from(o: JSONObject) = AccountConfig(
            name = o.optString("name", ""),
            on = o.optBoolean("on", false),
            ultra = o.optBoolean("ultra"),
            economy = o.optBoolean("economy"),
            muted = o.optBoolean("muted", true),
            lives = o.optBoolean("lives"),
            redeemTwitch = o.optStr("redeemTwitch")?.takeIf { it in KINDS },
            redeemKick = o.optStr("redeemKick")?.takeIf { it in KINDS },
            redeemTwitchOn = o.optBoolean("redeemTwitchOn", true),
            redeemKickOn = o.optBoolean("redeemKickOn", true),
            guard = o.optBoolean("guard", true),
            restock = o.optJSONObject("restock")?.let { RestockConfig.from(it) } ?: RestockConfig(),
        )
    }
}

class RestockConfig(
    var on: Boolean = false,
    var goldFloor: Long = 10_000,
    var potionFirst: Boolean = false,
    /** Pokebola: id do catálogo do jogo (1 = Poke Ball). */
    var balls: Rule = Rule(true, 1, 500, 100),
    var revives: Rule = Rule(true, 0, 10, 3),
    /** Poções: id 0 = a de melhor custo-benefício. */
    var potions: Rule = Rule(true, 0, 100, 30),
) {
    fun toJson(): JSONObject = JSONObject().put("on", on).put("goldFloor", goldFloor).put("potionFirst", potionFirst)
        .put("balls", balls.toJson()).put("revives", revives.toJson()).put("potions", potions.toJson())

    fun copy() = from(toJson())

    companion object {
        fun from(o: JSONObject): RestockConfig {
            val d = RestockConfig()
            return RestockConfig(
                on = o.optBoolean("on"),
                goldFloor = o.optLong("goldFloor", d.goldFloor).coerceAtLeast(0),
                potionFirst = o.optBoolean("potionFirst"),
                balls = o.optJSONObject("balls")?.let { Rule.from(it) } ?: d.balls,
                revives = o.optJSONObject("revives")?.let { Rule.from(it) } ?: d.revives,
                potions = o.optJSONObject("potions")?.let { Rule.from(it) } ?: d.potions,
            )
        }
    }
}

data class Rule(var on: Boolean, var id: Int, var buy: Int, var below: Int) {
    fun toJson(): JSONObject = JSONObject().put("on", on).put("id", id).put("buy", buy).put("below", below)

    companion object {
        fun from(o: JSONObject) = Rule(o.optBoolean("on"), o.optInt("id"), o.optInt("buy").coerceIn(0, 999_999), o.optInt("below").coerceAtLeast(0))
    }
}

/**
 * Os tempos do PC (Config.cs Timing) fixos: o mobile não tem a aba "Tempos e desempenho". Todos passam pelo Jitter.
 * FPS: com a conta na tela e escondida (tela apagada não tem quadro nenhum).
 */
object Timing {
    const val JITTER_PCT = 0.2
    const val LIVE_CHECK_SEC = 120L
    const val TWITCH_POINTS_SEC = 120L
    const val KICK_POINTS_SEC = 300L
    const val GUARD_SEC = 300L
    const val GUARD_THRESHOLD_MIN = 30
    const val BUY_GAP_MIN_SEC = 4L
    const val BUY_GAP_MAX_SEC = 8L
    const val REDEEM_RETRY_MIN = 30L
    const val FPS_SHOWN = 20
    const val FPS_HIDDEN = 5
    const val SPRITE_MS = 440
}

internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }

/** optString devolve "null" para JSON null; este devolve null de verdade. */
internal fun JSONObject.optStr(name: String): String? = opt(name) as? String // ausente ou JSON null → null
