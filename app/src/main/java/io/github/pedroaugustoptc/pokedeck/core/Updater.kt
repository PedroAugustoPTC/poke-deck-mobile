package io.github.pedroaugustoptc.pokedeck.core

import org.json.JSONException
import org.json.JSONObject

/** Uma release do GitHub com o PokeDeck.apk anexado e o SHA-256 que o GitHub publica dele. */
data class Release(val version: List<Int>, val tag: String, val page: String, val notes: String, val apkUrl: String, val sha256: String) {
    val versionName get() = version.joinToString(".")
}

/**
 * Atualização (regras do Updater.cs do PC): ao abrir, o app lê a release mais nova do repositório e, se for mais nova,
 * oferece baixar. Só aceita links do próprio repositório e só instala se o SHA-256 do arquivo baixado bater.
 */
object Updater {
    const val REPO = "https://github.com/PedroAugustoPTC/poke-deck-mobile"
    const val LATEST_URL = "https://api.github.com/repos/PedroAugustoPTC/poke-deck-mobile/releases/latest"
    const val ASSET = "PokeDeck.apk"

    /** "1.2.3" → [1, 2, 3]; null se não for X.Y.Z. */
    fun version(text: String): List<Int>? {
        val p = text.split(".")
        if (p.size !in 1..4) return null
        val n = p.map { it.toIntOrNull()?.takeIf { v -> v >= 0 } ?: return null }
        return (n + listOf(0, 0, 0)).take(3)
    }

    /** A resposta de /releases/latest; null se faltar o apk, o SHA-256 ou a tag não for vX.Y.Z. */
    fun parse(json: String): Release? = try {
        val root = JSONObject(json)
        val tag = root.optString("tag_name")
        val page = root.optString("html_url")
        val version = if (tag.startsWith("v")) version(tag.substring(1)) else null
        val assets = root.optJSONArray("assets")
        if (version == null || !page.startsWith("$REPO/releases/") || assets == null) null
        else (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.firstNotNullOfOrNull { a ->
            val url = a.optString("browser_download_url")
            val digest = a.optString("digest")
            if (a.optString("name") == ASSET && url.startsWith("$REPO/releases/download/") && digest.startsWith("sha256:") && digest.length == 7 + 64)
                Release(version, tag, page, root.optString("body"), url, digest.substring(7).lowercase())
            else null
        }
    } catch (_: JSONException) {
        null
    }

    /** A release é mais nova que o app. O build local (0.0.0) nunca se atualiza. */
    fun isNewer(current: String, release: Release): Boolean {
        val cur = version(current) ?: return false
        if (cur == listOf(0, 0, 0)) return false
        for (i in 0..2) if (release.version[i] != cur[i]) return release.version[i] > cur[i]
        return false
    }
}
