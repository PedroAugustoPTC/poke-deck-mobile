package io.github.pedroaugustoptc.pokedeck.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Leva a config entre o PokeDeck do PC e o do celular (espelho do Share.cs do PC), por código de texto ou arquivo .json.
 * Vai só o que é preferência: canais e lives, baú, horário de bloqueio e, por conta (pela posição), nome, ligada, ultra,
 * economia, lives, resgates, guarda e recompra. O que é do aparelho (mudo, tour, layout, zoom, tempos do PC) fica.
 * Formato: JSON camelCase com "v"; o código é "PKD1:" + base64url(gzip(json)). Também abre o config.json dos dois apps.
 */
object Share {
    const val VERSION = 1
    const val PREFIX = "PKD1:"
    private const val NOT_CONFIG = "Isto não é uma config do PokeDeck."
    private const val MAX_JSON = 256 * 1024

    class Invalid(message: String) : Exception(message)

    fun json(c: Config): JSONObject {
        val o = c.toJson()
        o.remove("tourDone")
        o.remove("setupDone")
        val accounts = o.getJSONArray("accounts")
        for (i in 0 until accounts.length()) accounts.getJSONObject(i).remove("muted")
        return o.put("v", VERSION).put("app", "pokedeck")
    }

    fun code(c: Config): String {
        val zipped = ByteArrayOutputStream()
        GZIPOutputStream(zipped).use { it.write(json(c).toString().toByteArray(Charsets.UTF_8)) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(zipped.toByteArray())
    }

    /** Um código ou um JSON aplicado sobre [current]: devolve uma config nova, com o que é do aparelho mantido. */
    fun read(text: String, current: Config): Config {
        val o = parse(text)
        if (o.optInt("v", VERSION) > VERSION) throw Invalid("Esta config é de uma versão mais nova do PokeDeck. Atualize o app.")
        if (!o.has("accounts") && !o.has("twitchChannels") && !o.has("kickChannels")) throw Invalid(NOT_CONFIG)
        val incoming = Config.from(o)
        val out = current.copy()
        if (o.has("twitchChannels")) out.twitchChannels = incoming.twitchChannels
        if (o.has("kickChannels")) out.kickChannels = incoming.kickChannels
        if (o.has("twitchLives")) out.twitchLives = incoming.twitchLives
        if (o.has("kickLives")) out.kickLives = incoming.kickLives
        if (o.has("claimChest")) out.claimChest = incoming.claimChest
        if (o.has("redeemBlockAt")) out.redeemBlockAt = incoming.redeemBlockAt
        o.optJSONArray("accounts")?.let { a ->
            val list = out.accounts.toMutableList()
            for (i in 0 until minOf(a.length(), list.size)) {
                val src = a.optJSONObject(i) ?: continue
                list[i] = AccountConfig.from(src).also { it.muted = list[i].muted }
            }
            out.accounts = list
        }
        return out.fix()
    }

    private fun parse(text: String): JSONObject {
        val t = text.trim().trimStart('﻿').trim()
        val json = when {
            t.startsWith(PREFIX, ignoreCase = true) -> try {
                val b64 = t.substring(PREFIX.length).filterNot { it.isWhitespace() }.replace('+', '-').replace('/', '_').trimEnd('=')
                GZIPInputStream(Base64.getUrlDecoder().decode(b64).inputStream()).use { z ->
                    val bytes = z.readNBytesCompat(MAX_JSON + 1)
                    if (bytes.size > MAX_JSON) throw Invalid(NOT_CONFIG)
                    bytes.toString(Charsets.UTF_8)
                }
            } catch (e: IllegalArgumentException) {
                throw Invalid("Código incompleto ou corrompido. Copie de novo, inteiro.")
            } catch (e: IOException) {
                throw Invalid("Código incompleto ou corrompido. Copie de novo, inteiro.")
            }
            t.startsWith("{") -> t
            else -> throw Invalid("Cole o código que começa com $PREFIX (ou abra o arquivo .json).")
        }
        return try {
            camel(JSONObject(json)) as JSONObject
        } catch (e: JSONException) {
            throw Invalid(NOT_CONFIG)
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** O config.json do PC tem as chaves em PascalCase ("TwitchChannels"); aqui tudo vira camelCase. */
    private fun camel(v: Any?): Any? = when (v) {
        is JSONObject -> JSONObject().also { r -> v.keys().forEach { k -> r.put(k.replaceFirstChar { it.lowercaseChar() }, camel(v.get(k))) } }
        is JSONArray -> JSONArray().also { r -> for (i in 0 until v.length()) r.put(camel(v.get(i))) }
        else -> v
    }
}
