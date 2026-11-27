package io.github.pedroaugustoptc.pokedeck.ui

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.pedroaugustoptc.pokedeck.R
import io.github.pedroaugustoptc.pokedeck.core.Log
import io.github.pedroaugustoptc.pokedeck.svc.Deck

/**
 * O registro em lista (design/screens/Registro.dc.html): resgates, baús, guarda e bloqueio, com filtro de conta e site.
 * "Geral" mostra o registro do app inteiro (recargas, logins, recompra), útil para ver o que aconteceu com o app fechado.
 */
class RegistroScreen(act: MainActivity) : Screen(act) {
    override val view = act.column()
    private var account = -1
    private var site = "Twitch e Kick"
    private val sites = listOf("Twitch e Kick", "Twitch", "Kick", "Geral")
    private var version = -1
    private var rows: List<Any> = emptyList()
    private lateinit var accountBtn: TextView
    private lateinit var siteBtn: TextView
    private val list = ListView(act).apply {
        divider = null
        setBackgroundColor(C.BG)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: act.column(4).apply {
                setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
                val top = add(act.row(8))
                top.add(act.chip("", C.TEXT, sizeSp = 12f), WRAP, WRAP)
                top.add(act.text("", 13f, C.WEAK), 0, WRAP, 1f)
                top.add(act.text("", 12f, C.WEAK), WRAP, WRAP)
                add(act.text("", 14f, C.LIGHT))
            }
            val top = v.getChildAt(0) as LinearLayout
            val tag = top.getChildAt(0) as TextView
            val who = top.getChildAt(1) as TextView
            val at = top.getChildAt(2) as TextView
            val detail = v.getChildAt(1) as TextView
            v.setBackgroundColor(if (p % 2 == 0) C.BG else C.PANEL)
            when (val r = rows[p]) {
                is Log.RedeemEntry -> {
                    tag.visibility = View.VISIBLE
                    tag.text = r.kind
                    val (fg, bg) = when (r.kind) {
                        "baú" -> C.OK to 0xFF173A24.toInt()
                        "resgate" -> C.GOLD_LIGHT to C.GOLD_DIM
                        "guarda" -> C.WARN to 0xFF3A2610.toInt()
                        "gasto fora" -> C.WEAK to C.WELL
                        else -> C.BAD to 0xFF4A1A1A.toInt() // recusado, baú recusado, devolvido, bloqueio
                    }
                    tag.setTextColor(fg)
                    tag.background = act.box(bg, radiusDp = 3)
                    who.text = "${Deck.config.accounts.getOrNull(r.account)?.name ?: "conta ${r.account + 1}"} · ${r.site}" +
                        if (r.channel != "—") " · ${r.channel}" else ""
                    at.text = r.at
                    detail.text = r.text
                }
                else -> {
                    tag.visibility = View.GONE
                    val line = r as String
                    at.text = line.take(14)
                    who.text = ""
                    detail.text = line.drop(15)
                }
            }
            return v
        }
    }

    init {
        with(act) {
            val header = view.add(row(8))
            header.setPadding(dp(10), dp(8), dp(10), dp(8))
            header.background = box(C.WOOD, C.WOOD_DARK, radiusDp = 0)
            header.add(button("Menu", R.drawable.ic_back) { act.pop() }, WRAP, WRAP)
            header.add(text("Registro", 22f, C.GOLD_LIGHT, Fonts.pixel), 0, WRAP, 1f)
            val filters = view.add(row(8))
            filters.setPadding(dp(12), dp(8), dp(12), dp(8))
            accountBtn = filters.add(button("") {
                val names = listOf("Todas as contas") + Deck.config.accounts.mapIndexed { k, a -> a.name.ifBlank { "Conta ${k + 1}" } }
                AlertDialog.Builder(act).setItems(names.toTypedArray()) { _, w -> account = w - 1; version = -1; refresh() }.show()
            }, 0, WRAP, 1f)
            siteBtn = filters.add(button("") {
                AlertDialog.Builder(act).setItems(sites.toTypedArray()) { _, w -> site = sites[w]; version = -1; refresh() }.show()
            }, 0, WRAP, 1f)
            view.add(list, MATCH, 0, 1f)
            list.adapter = adapter
        }
    }

    override fun refresh() {
        accountBtn.text = if (account < 0) "Todas as contas" else Deck.config.accounts[account].name.ifBlank { "Conta ${account + 1}" }
        siteBtn.text = site
        if (Log.version == version) return // nada novo: não refaz a lista
        version = Log.version
        rows = if (site == "Geral") Log.lines().filter { account < 0 || it.contains("[conta ${account + 1}]") }
        else Log.redeemLines().filter { (account < 0 || it.account == account) && (site == "Twitch e Kick" || it.site == site) }
        if (rows.isEmpty()) rows = listOf(" ".repeat(15) + "nada registrado ainda")
        adapter.notifyDataSetChanged()
    }
}
