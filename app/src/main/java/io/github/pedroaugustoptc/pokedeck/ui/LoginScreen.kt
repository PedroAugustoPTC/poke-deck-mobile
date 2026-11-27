package io.github.pedroaugustoptc.pokedeck.ui

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.pedroaugustoptc.pokedeck.R
import io.github.pedroaugustoptc.pokedeck.svc.Deck
import io.github.pedroaugustoptc.pokedeck.svc.Web
import kotlinx.coroutines.launch

/**
 * Entrar na Twitch ou na Kick no perfil da conta (como a janela de login do PC): os cookies ficam no perfil e os
 * watchers e os pontos passam a usá-los. Ao sair, grava a sessão no disco e confere as lives.
 */
class LoginScreen(act: MainActivity, private val i: Int, private val site: String) : Screen(act) {
    override val view = act.column()
    private val web: WebView = Web.create(act, Deck.accounts[i].profile)
    private var closed = false

    init {
        with(act) {
            val header = view.add(row(8))
            header.setPadding(dp(10), dp(8), dp(10), dp(8))
            header.background = box(C.WOOD, C.WOOD_DARK, radiusDp = 0)
            header.add(button("Menu", R.drawable.ic_back) { act.pop() }, WRAP, WRAP)
            header.add(text("Entrar na ${if (site == "kick") "Kick" else "Twitch"} — ${Deck.config.accounts[i].name}", 15f, font = Fonts.semi), 0, WRAP, 1f)
            header.add(button("Pronto", gold = true) { act.pop() }, WRAP, WRAP)
            view.add(web, MATCH, 0, 1f)
        }
        web.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!closed) act.pop()
                return true
            }
        }
        web.loadUrl(if (site == "kick") "https://kick.com/" else "https://www.twitch.tv/login")
    }

    override fun close() {
        if (closed) return
        closed = true
        Web.flush(Deck.accounts[i].profile)
        Web.destroy(web)
        Deck.scope.launch { Deck.checkLives() }
    }
}
