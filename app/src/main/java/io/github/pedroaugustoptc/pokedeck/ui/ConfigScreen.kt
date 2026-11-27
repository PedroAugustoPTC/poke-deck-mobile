package io.github.pedroaugustoptc.pokedeck.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import java.io.IOException
import io.github.pedroaugustoptc.pokedeck.R
import io.github.pedroaugustoptc.pokedeck.core.AccountConfig
import io.github.pedroaugustoptc.pokedeck.core.Config
import io.github.pedroaugustoptc.pokedeck.core.Rule
import io.github.pedroaugustoptc.pokedeck.core.Share
import io.github.pedroaugustoptc.pokedeck.svc.Deck

/**
 * Config (design/screens/Config.dc.html e ConfigLives.dc.html): abas Contas, Lives e Importar sobre uma cópia da config. Nada vale
 * até tocar em Salvar. Sem "Tempos e desempenho", voto e extensões (o mobile usa os tempos padrão do PC).
 */
class ConfigScreen(act: MainActivity) : Screen(act) {
    private var draft: Config = Deck.config.copy()
    override val view = act.column()
    private val body = act.column(14)
    private val tabs = ArrayList<TextView>()
    private var tab = 0
    private var acc = 0

    init {
        with(act) {
            val header = view.add(row(8))
            header.setPadding(dp(10), dp(8), dp(10), dp(8))
            header.background = box(C.WOOD, C.WOOD_DARK, radiusDp = 0)
            header.add(button("Menu", R.drawable.ic_back) { act.pop() }, WRAP, WRAP)
            header.add(text("Config", 22f, C.GOLD_LIGHT, Fonts.pixel), 0, WRAP, 1f)

            val tabRow = view.add(row())
            for ((k, name) in listOf("Contas", "Lives", "Importar").withIndex()) {
                tabs += tabRow.add(text(name, 15f, font = Fonts.cond).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(44)
                    setOnClickListener { tab = k; build() }
                }, 0, WRAP, 1f)
            }

            val scroll = view.add(ScrollView(act), MATCH, 0, 1f)
            scroll.addView(body)
            body.setPadding(dp(12), dp(14), dp(12), dp(24))

            val foot = view.add(row(8))
            foot.setPadding(dp(12), dp(8), dp(12), dp(8))
            foot.setBackgroundColor(C.PANEL)
            foot.add(button("Cancelar", minHeightDp = 48) { act.pop() }, 0, WRAP, 1f)
            foot.add(button("Salvar", minHeightDp = 48, gold = true) {
                Deck.setConfig(draft)
                act.pop()
            }, 0, WRAP, 1f)
        }
        build()
    }

    private fun build() {
        tabs.forEachIndexed { k, t ->
            t.setTextColor(if (k == tab) C.GOLD_LIGHT else C.WEAK)
            t.background = if (k == tab) act.box(C.PANEL_HIGH, C.GOLD, radiusDp = 0) else act.box(C.PANEL, radiusDp = 0)
        }
        body.removeAllViews()
        when (tab) {
            0 -> accountsTab()
            1 -> livesTab()
            else -> shareTab()
        }
    }

    // ---------------- Contas ----------------

    private fun accountsTab() = with(act) {
        val seg = body.add(row(6))
        draft.accounts.forEachIndexed { k, a ->
            seg.add(chip(a.name.ifBlank { "Conta ${k + 1}" }, if (k == acc) C.ON_GOLD else C.TEXT, if (k == acc) C.GOLD else C.WELL).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                maxLines = 1
                setOnClickListener { acc = k; build() }
            }, 0, WRAP, 1f)
        }
        val a = draft.accounts[acc]

        val card = body.add(panel(C.PANEL, C.THREAD, 4))
        card.add(title("A conta"))
        card.add(text("Nome", 13f, C.WEAK))
        card.add(field(a.name).apply { doAfterTextChanged { a.name = it.toString().trim() } })
        card.add(switchRow("Ligada", "entra sozinha ao abrir o app", a.on) { a.on = it }.first)
        card.add(switchRow("Economia", "menos gráfico, mais leve", a.economy) { a.economy = it }.first)
        card.add(switchRow("ULTRA", "velocidade máxima do jogo", a.ultra) { a.ultra = it }.first)
        card.add(switchRow("Mudo", "sem som nesta conta", a.muted) { a.muted = it }.first)

        val r = a.restock
        val rc = body.add(panel(C.PANEL, C.THREAD, 8))
        rc.add(switchRow("Recompra", null, r.on) { r.on = it }.first.apply { (getChildAt(0) as LinearLayout).getChildAt(0).let { (it as TextView).typeface = Fonts.pixel; it.setTextColor(C.HONEY); it.textSize = 18f } })
        rc.add(text("Abaixo do número, compra a quantidade. Gasta ouro de verdade: confira os valores.", 13f, C.WEAK))
        rc.add(text("Ouro mínimo", 13f, C.WEAK))
        rc.add(field(r.goldFloor.toString(), numeric = true).apply { doAfterTextChanged { r.goldFloor = it.toString().toLongOrNull() ?: 0 } })
        val head = rc.add(row(8))
        head.add(text("Item", 13f, C.WEAK), 0, WRAP, 1.4f)
        head.add(text("Abaixo de", 13f, C.WEAK), 0, WRAP, 1f)
        head.add(text("Compra", 13f, C.WEAK), 0, WRAP, 1f)
        fun rule(name: String, rule: Rule) {
            val line = rc.add(row(8))
            val left = line.add(row(8), 0, WRAP, 1.4f)
            left.add(Toggle(act, rule.on) { rule.on = it }, WRAP, WRAP)
            left.add(text(name, 15f, font = Fonts.semi), 0, WRAP, 1f)
            line.add(field(rule.below.toString(), numeric = true).apply { doAfterTextChanged { rule.below = it.toString().toIntOrNull() ?: 0 } }, 0, WRAP, 1f)
            line.add(field(rule.buy.toString(), numeric = true).apply { doAfterTextChanged { rule.buy = (it.toString().toIntOrNull() ?: 0).coerceAtMost(999_999) } }, 0, WRAP, 1f)
        }
        rule("Pokébola", r.balls)
        rule("Poção", r.potions)
        rule("Revive", r.revives)
        rc.add(button("Aplicar a todas as contas") {
            draft.accounts.forEach { if (it !== a) it.restock = r.copy() }
            AlertDialog.Builder(act).setMessage("A recompra desta conta foi copiada para as outras. Toque em Salvar para valer.").setPositiveButton("Ok", null).show()
        })

        body.add(button("Deixar rodando (bateria e permissões)") { act.push(SetupScreen(act, firstRun = false)) })
        body.add(button("Rever tour", R.drawable.ic_help) {
            act.pop()
            act.menu.startTour()
        })
    }

    // ---------------- Lives ----------------

    private fun livesTab() = with(act) {
        body.add(text("Um canal por linha, em ordem de prioridade. O app abre os primeiros que estiverem no ar, sem vídeo.", 14f, C.LIGHT))
        fun site(name: String, color: Int, channels: List<String>, setChannels: (List<String>) -> Unit, lives: Int, max: Int, note: String, setLives: (Int) -> Unit): LinearLayout {
            val card = body.add(panel(C.PANEL, C.THREAD, 8))
            val h = card.add(row(8))
            h.add(text(name, 16f, color, Fonts.bold), WRAP, WRAP)
            h.add(text("${channels.size} canais", 13f, C.WEAK), WRAP, WRAP)
            card.add(field(channels.joinToString("\n"), multiline = true).apply {
                doAfterTextChanged { setChannels(it.toString().split('\n', ',', ' ').map { c -> c.trim().lowercase() }.filter { c -> c.isNotEmpty() }) }
            })
            val st = card.add(row(8))
            val labels = st.add(column(1), 0, WRAP, 1f)
            labels.add(text("Lives por conta", 15f, font = Fonts.semi))
            labels.add(text(note, 13f, C.WEAK))
            var value = lives
            val shown = text(value.toString(), 16f, font = Fonts.bold).apply { gravity = Gravity.CENTER }
            st.add(button("−") { value = (value - 1).coerceAtLeast(0); shown.text = value.toString(); setLives(value) }, dp(44), dp(44))
            st.add(shown, dp(32), WRAP)
            st.add(button("+") { value = (value + 1).coerceAtMost(max); shown.text = value.toString(); setLives(value) }, dp(44), dp(44))
            return card
        }
        val tw = site("Twitch", C.TWITCH, draft.twitchChannels, { draft.twitchChannels = it }, draft.twitchLives, 5, "até 5") { draft.twitchLives = it }
        tw.add(switchRow("Pegar o baú de bônus sozinho", "+50 a cada ~15 min", draft.claimChest) { draft.claimChest = it }.first)
        site("Kick", C.KICK, draft.kickChannels, { draft.kickChannels = it }, draft.kickLives, 2, "a Kick só conta 2") { draft.kickLives = it }

        body.add(title("Por conta"))
        body.add(text("Vincule a Twitch e a Kick no jogo em cada conta que resgata.", 13f, C.WEAK))
        draft.accounts.forEachIndexed { k, a -> accountLives(k, a) }

        val block = body.add(panel(C.PANEL, C.THREAD, 6))
        block.add(text("Horário de bloqueio do resgate", 15f, font = Fonts.semi))
        lateinit var bf: TextView
        bf = block.add(button(draft.redeemBlockAt ?: "sem bloqueio") {
            pickBlock(act, draft.redeemBlockAt) { v -> draft.redeemBlockAt = v; bf.text = v ?: "sem bloqueio" }
        }, WRAP, WRAP)
        block.add(text("Se o buff de uma conta já passa deste horário, ela não resgata e desliga o resgate daquele site.", 13f, C.WEAK))
    }

    private fun accountLives(k: Int, a: AccountConfig) = with(act) {
        val card = body.add(panel(C.PANEL, C.THREAD, 4))
        card.add(text(a.name.ifBlank { "Conta ${k + 1}" }, 16f, font = Fonts.bold))
        card.add(switchRow("Assiste", "abre as lives com a Twitch/Kick logadas nela", a.lives) { a.lives = it }.first)
        card.add(choice("Resgate Twitch", a.redeemTwitch) { a.redeemTwitch = it })
        card.add(choice("Resgate Kick", a.redeemKick) { a.redeemKick = it })
        card.add(switchRow("Guarda", "resgata perto de o bônus vencer", a.guard) { a.guard = it }.first)
    }

    // ---------------- Importar ----------------

    private fun shareTab() = with(act) {
        body.add(text("Leva a config entre o celular e o PokeDeck do PC: canais e ordem das lives, quantas lives, baú, horário de " +
            "bloqueio e, por conta, nome, ligada, ULTRA, economia, lives, resgates, guarda e recompra. Mudo fica como está aqui.", 14f, C.LIGHT))

        val code = body.add(panel(C.PANEL, C.THREAD, 8))
        code.add(title("Código"))
        code.add(text("Um texto que começa com ${Share.PREFIX}. Mande para você mesmo (WhatsApp, Telegram, e-mail) e cole no outro aparelho.", 13f, C.WEAK))
        val cb = act.getSystemService(ClipboardManager::class.java)
        code.add(button("Copiar código") {
            cb.setPrimaryClip(ClipData.newPlainText("Config do PokeDeck", Share.code(draft)))
            say("Código copiado. Cole no PokeDeck do PC (Config → Importar/exportar → Colar código).")
        })
        code.add(button("Colar código") {
            val clip = cb.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString()
            read(clip.orEmpty())
        })

        val file = body.add(panel(C.PANEL, C.THREAD, 8))
        file.add(title("Arquivo"))
        file.add(button("Exportar arquivo") {
            val pick = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "pokedeck-config.json")
            act.pickFile(pick) { uri ->
                if (uri == null) return@pickFile
                try {
                    act.contentResolver.openOutputStream(uri, "wt")?.use { it.write(Share.json(draft).toString(1).toByteArray()) }
                        ?: throw IOException("sem acesso ao arquivo")
                    say("Config exportada.")
                } catch (e: IOException) {
                    say("Não deu para salvar: ${e.message}")
                } catch (e: SecurityException) {
                    say("Não deu para salvar: ${e.message}")
                }
            }
        })
        file.add(button("Importar arquivo") {
            act.pickFile(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")) { uri ->
                if (uri == null) return@pickFile
                try {
                    val bytes = act.contentResolver.openInputStream(uri)?.use { s -> s.readNBytes1M() } ?: throw IOException("sem acesso ao arquivo")
                    if (bytes.size > MAX_FILE) say("Arquivo grande demais para ser uma config do PokeDeck.")
                    else read(bytes.toString(Charsets.UTF_8))
                } catch (e: IOException) {
                    say("Não deu para abrir: ${e.message}")
                } catch (e: SecurityException) {
                    say("Não deu para abrir: ${e.message}")
                }
            }
        })
        body.add(text("Importar só muda esta tela: nada vale até tocar em Salvar. Aqui a Kick vai até 2 lives.", 13f, C.WEAK))
    }

    private fun read(text: String) {
        try {
            draft = Share.read(text, draft)
            say("Config importada. Confira as abas e toque em Salvar para valer (Cancelar descarta).")
        } catch (e: Share.Invalid) {
            say(e.message ?: "Não deu para importar.")
        }
    }

    private fun say(msg: String) {
        AlertDialog.Builder(act).setMessage(msg).setPositiveButton("Ok", null).show()
    }

    private fun java.io.InputStream.readNBytes1M(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() <= MAX_FILE) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_FILE = 1024 * 1024
    }

    private val kinds = listOf("+15% XP" to "xp", "Captura" to "captura", "Shiny" to "shiny", "Nada" to null)

    private fun choice(label: String, value: String?, set: (String?) -> Unit): View = with(act) {
        val r = row(10).apply { minimumHeight = dp(52) }
        r.add(text(label, 15f, font = Fonts.semi), 0, WRAP, 1f)
        lateinit var b: TextView
        b = r.add(button(kinds.first { it.second == value }.first) {
            AlertDialog.Builder(act).setTitle(label).setItems(kinds.map { it.first }.toTypedArray()) { _, which ->
                set(kinds[which].second)
                b.text = kinds[which].first
            }.show()
        }, WRAP, WRAP)
        r
    }
}
