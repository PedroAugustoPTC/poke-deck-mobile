package io.github.pedroaugustoptc.pokedeck.core

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Os registros: o geral (pokedeck.log) e o dos resgates (resgates.log, TSV igual ao do PC: quando, conta, site, canal,
 * tipo, texto), que sobrevive entre aberturas. Guarda as últimas linhas em memória para a tela Registro.
 */
object Log {
    data class RedeemEntry(val at: String, val account: Int, val site: String, val channel: String, val kind: String, val text: String)

    private const val KEEP = 300
    private const val MAX_BYTES = 512 * 1024L
    private val stamp = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")
    private val recent = ArrayDeque<String>()
    private val redeems = ArrayDeque<RedeemEntry>()

    var filePath: File? = null
    var redeemPath: File? = null

    /** Muda a cada linha nova: a tela Registro relê só quando mudou. */
    @Volatile
    var version = 0
        private set

    fun write(account: Int?, text: String) {
        val line = "${LocalDateTime.now().format(stamp)} ${if (account != null) "[conta ${account + 1}] " else ""}$text"
        synchronized(this) {
            push(recent, line)
            append(filePath, line)
            version++
        }
    }

    /** Um evento de pontos/resgate (também vai para o registro geral). */
    fun redeem(account: Int, site: String, channel: String, kind: String, text: String) {
        val e = RedeemEntry(LocalDateTime.now().format(stamp), account, site, channel, kind, text)
        synchronized(this) {
            push(redeems, e)
            append(redeemPath, listOf(e.at, account, site, channel, kind, text.replace('\t', ' ')).joinToString("\t"))
        }
        write(account, "$site $channel: $text")
    }

    /** Relê o fim do resgates.log ao abrir o app. */
    fun loadRedeems() {
        val f = redeemPath ?: return
        if (!f.exists()) return
        try {
            synchronized(this) { f.readLines().takeLast(KEEP).mapNotNull { parse(it) }.forEach { push(redeems, it) } }
        } catch (_: IOException) {
        }
    }

    fun parse(line: String): RedeemEntry? {
        val p = line.split('\t')
        val account = p.getOrNull(1)?.toIntOrNull() ?: return null
        return if (p.size == 6) RedeemEntry(p[0], account, p[2], p[3], p[4], p[5]) else null
    }

    fun lines(): List<String> = synchronized(this) { recent.reversed() }

    fun redeemLines(): List<RedeemEntry> = synchronized(this) { redeems.reversed() }

    private fun <T> push(q: ArrayDeque<T>, item: T) {
        q.addLast(item)
        while (q.size > KEEP) q.removeFirst()
    }

    private fun append(f: File?, line: String) {
        f ?: return
        try {
            // Não cresce sem fim: passou de 512 KB, fica com as últimas 2000 linhas.
            if (f.length() > MAX_BYTES) f.writeText(f.readLines().takeLast(2000).joinToString("\n", postfix = "\n"))
            f.appendText(line + "\n")
        } catch (_: IOException) {
        }
    }
}
