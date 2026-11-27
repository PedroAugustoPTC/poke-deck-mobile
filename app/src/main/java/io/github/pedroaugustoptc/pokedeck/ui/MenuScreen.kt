package io.github.pedroaugustoptc.pokedeck.ui

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.pedroaugustoptc.pokedeck.R
import io.github.pedroaugustoptc.pokedeck.core.AccountConfig
import io.github.pedroaugustoptc.pokedeck.core.BonusSite
import io.github.pedroaugustoptc.pokedeck.svc.Account
import io.github.pedroaugustoptc.pokedeck.svc.Deck
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** Texto e cor do estado da conta (os mesmos do PC). */
fun phaseOf(a: Account): Pair<String, Int> = when (a.phase) {
    "game" -> "jogando" to C.OK
    "loading" -> "entrando…" to C.WARN
    "login" -> "tela de login" to C.WARN
    "failed" -> "não carregou" to C.BAD
    "elsewhere" -> "aberta em outro lugar" to C.BAD
    else -> "desligada" to C.WEAK
}

val gold: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("pt-BR"))

/** "T · xp 25m": o bônus escolhido no site (ou o que tem mais tempo) e quanto falta. */
fun buffText(letter: String, bonus: BonusSite?, chosen: String?): String {
    val left = bonus?.left ?: return "$letter · —"
    val kind = chosen ?: left.maxByOrNull { it.value }?.key ?: return "$letter · —"
    val sec = left[kind] ?: 0
    if (sec <= 0) return "$letter · —"
    val min = sec / 60
    return "$letter · $kind " + if (min >= 60) "${min / 60}h${"%02d".format(min % 60)}" else "${min}m"
}

/** A tela principal: as 4 contas, as lives, os ajustes rápidos e o rodapé (design/screens/Main.dc.html). */
class MenuScreen(act: MainActivity) : Screen(act) {
    private val scroll = ScrollView(act).apply { isFillViewport = true }
    override val view: View = FrameLayout(act).apply { addView(scroll) }
    private val bars = ArrayList<Bar>()
    private lateinit var twitchCount: TextView
    private lateinit var kickCount: TextView
    private lateinit var watching: LinearLayout
    private lateinit var blockField: TextView
    private lateinit var powerAll: TextView
    private val quick = HashMap<String, Toggle>()
    private var tour: TourOverlay? = null

    // Alvos do tour.
    lateinit var tourBtn: View
    lateinit var configBtn: View
    lateinit var accountsSection: View
    lateinit var livesSection: View
    lateinit var quickSection: View
    val firstBar get() = bars[0].view

    private inner class Bar(val i: Int) {
        val view = act.column(8)
        val dot = View(act)
        val name = act.text("", 16f, font = Fonts.bold)
        val status = act.text("", 13f)
        val goldPill = act.chip("", C.GOLD_LIGHT).apply { typeface = Fonts.semi; icon(R.drawable.ic_coin, C.GOLD, 12) }
        val buffT = act.chip("", C.TWITCH)
        val buffK = act.chip("", C.KICK)
        val lives = act.text("", 13f, C.WEAK)
        val giftT = act.chip("T", C.WEAK, sizeSp = 12f).apply { icon(R.drawable.ic_gift, sizeDp = 12); typeface = Fonts.semi }
        val giftK = act.chip("K", C.WEAK, sizeSp = 12f).apply { icon(R.drawable.ic_gift, sizeDp = 12); typeface = Fonts.semi }
        val cart = act.chip("", C.WEAK).apply { icon(R.drawable.ic_cart, sizeDp = 13); compoundDrawablePadding = 0 }

        init {
            with(act) {
                view.setPadding(dp(12), dp(10), dp(10), dp(10))
                view.isClickable = true
                view.setOnClickListener { act.push(ContaScreen(act, i)) }
                val top = view.add(row(8))
                top.add(dot, dp(9), dp(9))
                top.add(name, WRAP, WRAP)
                top.add(status, 0, WRAP, 1f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                top.add(goldPill, WRAP, WRAP)
                top.add(ImageView(act).apply { setImageResource(R.drawable.ic_chevron); imageTintList = android.content.res.ColorStateList.valueOf(C.WEAK) }, dp(16), dp(16))
                val bottom = view.add(row(6))
                bottom.add(buffT, WRAP, WRAP)
                bottom.add(buffK, WRAP, WRAP)
                bottom.add(spacer(), 0, 1, 1f)
                bottom.add(lives, WRAP, WRAP)
                bottom.add(giftT, WRAP, WRAP)
                bottom.add(giftK, WRAP, WRAP)
                bottom.add(cart, WRAP, WRAP)
            }
        }

        fun update(a: Account, c: AccountConfig) {
            val (st, color) = phaseOf(a)
            dot.background = act.box(color, radiusDp = 1)
            name.text = c.name.ifBlank { "Conta ${i + 1}" }
            status.text = st
            status.setTextColor(color)
            val s = a.state
            goldPill.text = s?.let { gold.format(it.gold) } ?: "—"
            buffT.text = buffText("T", s?.twitch, c.redeemTwitch)
            buffK.text = buffText("K", s?.kick, c.redeemKick)
            lives.text = "T ${a.twitchOpen.size} · K ${a.kickOpen.size}"
            giftT.flag(c.redeemTwitch != null && c.redeemTwitchOn)
            giftK.flag(c.redeemKick != null && c.redeemKickOn)
            cart.flag(c.restock.on)
            giftT.contentDescription = "Resgate Twitch " + if (c.redeemTwitchOn) "ligado" else "desligado"
            giftK.contentDescription = "Resgate Kick " + if (c.redeemKickOn) "ligado" else "desligado"
            cart.contentDescription = "Recompra " + if (c.restock.on) "ligada" else "desligada"
            view.contentDescription = "Abrir ${name.text}, $st"
            view.background = act.box(C.PANEL, if (a.game != null && a.phase == "game") C.GOLD else C.WOOD_DARK, strokeDp = 2)
        }
    }

    init {
        with(act) {
            val page = column()
            scroll.addView(page)

            val header = page.add(row(8))
            header.setPadding(dp(14), dp(10), dp(10), dp(10))
            header.background = box(C.WOOD, C.WOOD_DARK, radiusDp = 0)
            header.add(ImageView(act).apply { setImageResource(R.drawable.logo) }, dp(28), dp(28))
            header.add(text("PokeDeck", 22f, C.GOLD_LIGHT, Fonts.pixel), 0, WRAP, 1f)
            tourBtn = header.add(iconButton(R.drawable.ic_help, "Tour") { startTour() }, dp(44), dp(44))
            configBtn = header.add(iconButton(R.drawable.ic_gear, "Config") { act.push(ConfigScreen(act)) }, dp(44), dp(44))

            val main = page.add(column(18))
            main.setPadding(dp(12), dp(14), dp(12), dp(24))

            val accounts = main.add(column(8))
            accountsSection = accounts
            val h1 = accounts.add(row(8))
            h1.add(title("Contas"), WRAP, WRAP)
            h1.add(text("entram sozinhas ao abrir", 13f, C.WEAK), WRAP, WRAP)
            for (i in 0 until 4) bars += Bar(i).also { accounts.add(it.view) }

            val lives = main.add(panel())
            livesSection = lives
            val h2 = lives.add(row(8))
            h2.add(title("Lives"), WRAP, WRAP)
            h2.add(text("sem vídeo, só os pontos", 13f, C.WEAK), 0, WRAP, 1f)
            h2.add(button("Conferir", R.drawable.ic_refresh) { Deck.scope.launch { Deck.checkLives(); refresh() } }, WRAP, WRAP)
            val counts = lives.add(row(8))
            fun site(name: String, color: Int): TextView {
                val c = counts.add(column(2), 0, WRAP, 1f)
                c.background = box(C.PANEL)
                c.setPadding(dp(10), dp(8), dp(10), dp(8))
                c.add(text(name, 14f, color, Fonts.bold))
                return c.add(text("", 14f))
            }
            twitchCount = site("Twitch", C.TWITCH)
            kickCount = site("Kick", C.KICK)
            watching = lives.add(column())

            val q = main.add(column())
            quickSection = q
            q.background = box(C.PANEL, C.THREAD)
            q.setPadding(dp(12), dp(4), dp(12), dp(4))
            fun quickRow(key: String, name: String, note: String, set: (AccountConfig, Boolean) -> Unit) {
                val (r, t) = switchRow(name, note, false) { on -> Deck.edit { c -> c.accounts.forEach { set(it, on) } } }
                quick[key] = t
                q.add(r)
                q.add(divider(), MATCH, dp(1))
            }
            quickRow("economy", "Economia", "jogo mais leve em todas as contas") { a, on -> a.economy = on }
            quickRow("muted", "Mudo", "sem som em todas") { a, on -> a.muted = on }
            quickRow("ultra", "ULTRA", "velocidade máxima do jogo") { a, on -> a.ultra = on }
            val blockRow = q.add(row(10).apply { minimumHeight = dp(52) })
            val bt = blockRow.add(column(1), 0, WRAP, 1f)
            bt.add(text("Bloqueio do resgate", 15f, font = Fonts.semi))
            bt.add(text("buff que passa disso não resgata", 13f, C.WEAK))
            blockField = blockRow.add(text("", 15f, font = Fonts.semi).apply {
                gravity = Gravity.CENTER
                minHeight = dp(36)
                setPadding(dp(10), 0, dp(10), 0)
                background = box(C.BG, C.THREAD)
                setOnClickListener { pickBlock(act, Deck.config.redeemBlockAt) { v -> Deck.edit { it.redeemBlockAt = v } } }
            }, WRAP, WRAP)

            val foot = main.add(row(8))
            powerAll = foot.add(button("Desligar todas", R.drawable.ic_power) {
                if (Deck.config.accounts.none { it.on }) {
                    Deck.edit { c -> c.accounts.forEach { it.on = true } }
                    refresh()
                    return@button
                }
                AlertDialog.Builder(act).setTitle("Desligar todas as contas?")
                    .setMessage("O jogo e as lives param em todas. Para religar, toque em \"Ligar todas\" ou abra a conta e toque em ligar.")
                    .setPositiveButton("Desligar") { _, _ -> Deck.edit { c -> c.accounts.forEach { it.on = false } }; refresh() }
                    .setNegativeButton("Cancelar", null).show()
            }, 0, WRAP, 1f)
            foot.add(button("Registro", R.drawable.ic_list) { act.push(RegistroScreen(act)) }, 0, WRAP, 1f)
        }
    }

    override fun refresh() {
        val cfg = Deck.config
        Deck.accounts.forEachIndexed { i, a -> bars[i].update(a, cfg.accounts[i]) }
        val tOn = Deck.twitchLive.count { it.value == true }
        twitchCount.text = "$tOn no ar" + if (cfg.claimChest) " · baú sozinho" else ""
        kickCount.text = "${Deck.kickLive.count { it.value == true }} no ar"
        val sig = Deck.accounts.joinToString("|") { cfg.accounts[it.index].name + it.twitchOpen + it.kickOpen }
        if (sig != watchSig) rebuildWatching(sig)
        quick["economy"]?.on = cfg.accounts.all { it.economy }
        quick["muted"]?.on = cfg.accounts.all { it.muted }
        quick["ultra"]?.on = cfg.accounts.all { it.ultra }
        blockField.text = cfg.redeemBlockAt ?: "sem"
        powerAll.text = if (cfg.accounts.none { it.on }) "Ligar todas" else "Desligar todas"
        tour?.place()
    }

    private var watchSig = "-"

    /** A lista "quem assiste o quê" só é refeita quando muda (a tela atualiza a cada segundo). */
    private fun rebuildWatching(sig: String) {
        watchSig = sig
        val cfg = Deck.config
        watching.removeAllViews()
        for (a in Deck.accounts) {
            if (a.twitchOpen.isEmpty() && a.kickOpen.isEmpty()) continue
            watching.add(act.divider(), MATCH, act.dp(1))
            val r = watching.add(act.row(8))
            r.setPadding(0, act.dp(7), 0, act.dp(7))
            r.add(act.text(cfg.accounts[a.index].name, 14f, font = Fonts.semi), act.dp(86), WRAP)
            r.add(act.text(listOf(a.twitchOpen.joinToString(", "), a.kickOpen.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · "), 13f, C.LIGHT), 0, WRAP, 1f)
        }
        if (watching.childCount == 0) watching.add(act.text("nenhuma conta assistindo (Config › Lives)", 13f, C.WEAK))
    }

    fun startTour() {
        if (tour != null) return
        scroll.scrollTo(0, 0)
        tour = TourOverlay(act, this, scroll) {
            (view as FrameLayout).removeView(tour)
            tour = null
            if (!Deck.config.tourDone) Deck.edit { it.tourDone = true }
        }.also { (view as FrameLayout).addView(it) }
    }
}

/** O horário de bloqueio: relógio do Android, com "Sem bloqueio" para limpar. */
fun pickBlock(act: MainActivity, current: String?, set: (String?) -> Unit) {
    val cur = current?.split(":")
    val dlg = TimePickerDialog(act, { _, h, m -> set("%02d:%02d".format(h, m)) }, cur?.get(0)?.toInt() ?: 23, cur?.get(1)?.toInt() ?: 0, true)
    dlg.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "Sem bloqueio") { _, _ -> set(null) }
    dlg.show()
}
