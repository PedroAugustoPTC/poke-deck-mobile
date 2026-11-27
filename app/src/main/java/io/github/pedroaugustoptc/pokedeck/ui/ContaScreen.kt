package io.github.pedroaugustoptc.pokedeck.ui

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import io.github.pedroaugustoptc.pokedeck.R
import io.github.pedroaugustoptc.pokedeck.svc.Account
import io.github.pedroaugustoptc.pokedeck.svc.Deck

/**
 * A conta aberta por cima do menu (design/screens/Conta.dc.html): cabeçalho igual ao do PC, a linha de botões e o jogo
 * em tela cheia. O jogo é o mesmo navegador que roda escondido: entra e sai daqui sem recarregar.
 */
class ContaScreen(act: MainActivity, private val i: Int) : Screen(act) {
    private val a: Account get() = Deck.accounts[i]
    private val cfg get() = Deck.config.accounts[i]
    override val view = act.column()
    private val dot = View(act)
    private val name = act.text("", 16f, font = Fonts.bold)
    private val goldPill = act.chip("", C.GOLD_LIGHT).apply { typeface = Fonts.semi; icon(R.drawable.ic_coin, C.GOLD, 12) }
    private val buffT = act.chip("", C.TWITCH)
    private val buffK = act.chip("", C.KICK)
    private val status = act.text("", 13f)
    private lateinit var tBtn: TextView
    private lateinit var kBtn: TextView
    private lateinit var giftT: TextView
    private lateinit var giftK: TextView
    private lateinit var cart: TextView
    private lateinit var power: View
    private val stage = FrameLayout(act).apply { setBackgroundColor(C.BG) }
    private val offNote = act.text("", 15f, C.WEAK).apply { gravity = Gravity.CENTER; setPadding(act.dp(24), 0, act.dp(24), 0) }
    private var loggedT = false
    private var loggedK = false
    private var loginCheckAt = 0L

    init {
        with(act) {
            val header = view.add(column(8))
            header.setPadding(dp(10), dp(8), dp(10), dp(8))
            header.background = box(C.WOOD, C.WOOD_DARK, radiusDp = 0)
            val top = header.add(row(8))
            top.add(button("Menu", R.drawable.ic_back) { act.pop() }, WRAP, WRAP)
            top.add(dot, dp(9), dp(9))
            top.add(name, 0, WRAP, 1f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            top.add(goldPill, WRAP, WRAP)
            val chips = header.add(row(6))
            chips.add(buffT, WRAP, WRAP)
            chips.add(buffK, WRAP, WRAP)
            chips.add(status, 0, WRAP, 1f).apply { gravity = Gravity.END; maxLines = 1 }

            // Os 7 botões dividem a largura: cabem de 320 dp em diante, com a fonte que for.
            val bar = header.add(row(6))
            fun <T : View> cell(v: T): T = bar.add(v, 0, dp(44), 1f)
            tBtn = cell(button("T", minHeightDp = 44) { lives("twitch") }.apply { setPadding(0, 0, 0, 0); setTextColor(C.TWITCH) })
            kBtn = cell(button("K", minHeightDp = 44) { lives("kick") }.apply { setPadding(0, 0, 0, 0); setTextColor(C.KICK) })
            giftT = cell(chip("T", C.WEAK).apply { gravity = Gravity.CENTER; icon(R.drawable.ic_gift, sizeDp = 14); setOnClickListener { toggleRedeem("twitch") } })
            giftK = cell(chip("K", C.WEAK).apply { gravity = Gravity.CENTER; icon(R.drawable.ic_gift, sizeDp = 14); setOnClickListener { toggleRedeem("kick") } })
            cart = cell(chip("", C.WEAK).apply {
                gravity = Gravity.CENTER
                icon(R.drawable.ic_cart, sizeDp = 16)
                compoundDrawablePadding = 0
                contentDescription = "Recompra"
                setOnClickListener { Deck.edit { it.accounts[i].restock.on = !it.accounts[i].restock.on } ; refresh() }
            })
            cell(iconButton(R.drawable.ic_refresh, "Recarregar") { a.reload() })
            power = cell(iconButton(R.drawable.ic_power, "Desligar esta conta") { togglePower() })

            view.add(stage, MATCH, 0, 1f)
            stage.add(offNote, MATCH, MATCH)
        }
    }

    override fun resume() = attach()

    private fun attach() {
        if (a.game != null && a.game?.parent !== stage) Deck.show(i, stage)
    }

    override fun close() = Deck.hide()

    override fun refresh() {
        attach()
        val (st, color) = phaseOf(a)
        dot.background = act.box(color, radiusDp = 1)
        name.text = cfg.name.ifBlank { "Conta ${i + 1}" }
        status.text = st
        status.setTextColor(color)
        val s = a.state
        goldPill.text = s?.let { gold.format(it.gold) } ?: "—"
        buffT.text = buffText("T", s?.twitch, cfg.redeemTwitch)
        buffK.text = buffText("K", s?.kick, cfg.redeemKick)
        val now = System.currentTimeMillis()
        if (now > loginCheckAt) { // ler cookies a cada 3 s basta
            loginCheckAt = now + 3_000
            loggedT = a.twitchLogged()
            loggedK = a.kickLogged()
        }
        tBtn.text = "T ${a.twitchOpen.size}"
        kBtn.text = "K ${a.kickOpen.size}"
        tBtn.alpha = if (loggedT) 1f else 0.45f
        kBtn.alpha = if (loggedK) 1f else 0.45f
        tBtn.contentDescription = if (loggedT) "Twitch: ${a.twitchOpen.size} lives abertas" else "Twitch: sem login. Toque para entrar"
        kBtn.contentDescription = if (loggedK) "Kick: ${a.kickOpen.size} lives abertas" else "Kick: sem login. Toque para entrar"
        giftT.flag(cfg.redeemTwitch != null && cfg.redeemTwitchOn)
        giftK.flag(cfg.redeemKick != null && cfg.redeemKickOn)
        cart.flag(cfg.restock.on)
        power.alpha = if (a.game != null) 1f else 0.6f
        power.contentDescription = if (a.game != null) "Desligar esta conta" else "Ligar esta conta"
        offNote.visibility = if (a.game == null) View.VISIBLE else View.GONE
        offNote.text = "Conta desligada.\nToque no botão de ligar (o último da linha) para ela entrar no jogo."
    }

    private fun togglePower() {
        if (a.game == null) {
            Deck.edit { it.accounts[i].on = true }
            refresh()
            return
        }
        AlertDialog.Builder(act).setTitle("Desligar ${cfg.name}?")
            .setMessage("O jogo e as lives desta conta param até você ligar de novo.")
            .setPositiveButton("Desligar") { _, _ ->
                Deck.edit { it.accounts[i].on = false }
                refresh()
            }.setNegativeButton("Cancelar", null).show()
    }

    /** Sem login: abre o navegador da conta para entrar. Com login: as lives abertas e o saldo de cada uma. */
    private fun lives(site: String) {
        val twitch = site == "twitch"
        if (!(if (twitch) loggedT else loggedK)) {
            act.push(LoginScreen(act, i, site))
            return
        }
        val open = if (twitch) a.twitchOpen else a.kickOpen
        val pts = if (twitch) a.tp else a.kp
        val lines = open.map { c -> "$c — ${if (twitch) a.twitchStatus(c) else a.kickStatus(c)}  ${pts.channel(c)?.text ?: ""}" }
        val extra = listOfNotNull(pts.stopped, if (twitch) a.pubsubError else null)
        AlertDialog.Builder(act).setTitle(if (twitch) "Twitch" else "Kick")
            .setMessage((if (lines.isEmpty()) listOf(if (cfg.lives) "Nenhuma live aberta agora." else "Esta conta não assiste (Config › Lives › assiste).") else lines)
                .plus(extra).joinToString("\n"))
            .setPositiveButton("Ok", null)
            .setNeutralButton("Entrar de novo") { _, _ -> act.push(LoginScreen(act, i, site)) }
            .show()
    }

    /** Liga/desliga o resgate do site; religar com o buff já passando do bloqueio pede confirmação (igual ao PC). */
    private fun toggleRedeem(site: String) {
        val twitch = site == "twitch"
        if ((if (twitch) cfg.redeemTwitch else cfg.redeemKick) == null) {
            AlertDialog.Builder(act).setMessage("Escolha o bônus do resgate em Config › Lives › Por conta.").setPositiveButton("Ok", null).show()
            return
        }
        val on = if (twitch) cfg.redeemTwitchOn else cfg.redeemKickOn
        val flip = {
            Deck.edit { c ->
                if (twitch) {
                    c.accounts[i].redeemTwitchOn = !on
                } else {
                    c.accounts[i].redeemKickOn = !on
                }
            }
            refresh()
        }
        if (!on && a.covered(site))
            AlertDialog.Builder(act).setTitle("Religar o resgate?")
                .setMessage("O bônus desta conta já passa do bloqueio das ${Deck.config.redeemBlockAt}. Religado, ela resgata até esse horário passar.")
                .setPositiveButton("Religar") { _, _ -> flip() }.setNegativeButton("Cancelar", null).show()
        else flip()
    }
}
