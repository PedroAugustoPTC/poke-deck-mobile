package io.github.pedroaugustoptc.pokedeck.svc

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URLDecoder

/**
 * Os navegadores do app. Cada conta tem um perfil próprio ("contaN": cookies, localStorage e logins separados), como os
 * perfis do WebView2 no PC. Só a thread principal mexe aqui.
 */
object Web {
    /** O WebView do aparelho tem tudo de que o app precisa (perfis, script antes da página, mensagens da página). */
    fun supported(): Boolean = listOf(WebViewFeature.MULTI_PROFILE, WebViewFeature.DOCUMENT_START_SCRIPT, WebViewFeature.WEB_MESSAGE_LISTENER)
        .all { WebViewFeature.isFeatureSupported(it) }

    /** Tira as marcas de WebView do user-agent ("; wv", "Version/4.0"): a Twitch e a Kick tratam como o Chrome do celular. */
    fun cleanUa(ua: String) = ua.replace("; wv)", ")").replace(Regex(" Version/[\\d.]+"), "")

    fun defaultUa(context: Context) = cleanUa(WebSettings.getDefaultUserAgent(context))

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, profile: String): WebView {
        // A conta muda de janela (serviço ↔ tela) sem recriar. Tela bloqueada esconde a janela (inclusive a sobreposição) e o
        // Chromium congelaria os timers da página até o socket do jogo cair: o navegador se acha sempre visível.
        val view = object : WebView(MutableContextWrapper(context)) {
            override fun onWindowVisibilityChanged(visibility: Int) = super.onWindowVisibilityChanged(android.view.View.VISIBLE)
        }
        ProfileStore.getInstance().getOrCreateProfile(profile)
        WebViewCompat.setProfile(view, profile) // antes de qualquer outra coisa no WebView
        view.setBackgroundColor(Color.rgb(20, 10, 15))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = cleanUa(userAgentString)
            mediaPlaybackRequiresUserGesture = true
            textZoom = 100 // a fonte grande do sistema não deforma o jogo
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        cookies(profile).setAcceptThirdPartyCookies(view, true)
        // O processo do navegador não vira "descartável" quando a conta está escondida.
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        return view
    }

    fun cookies(profile: String) = ProfileStore.getInstance().getOrCreateProfile(profile).cookieManager

    /** Os cookies de [url] no perfil, nome → valor. */
    fun cookieMap(profile: String, url: String): Map<String, String> =
        (cookies(profile).getCookie(url) ?: "").split(';').mapNotNull {
            val p = it.trim().split('=', limit = 2)
            if (p.size == 2 && p[0].isNotEmpty()) p[0] to p[1] else null
        }.toMap()

    fun decode(v: String): String = URLDecoder.decode(v, "UTF-8")

    /** Grava os cookies no disco já: se o Android matar o app, a sessão volta sem novo login. */
    fun flush(profile: String) = cookies(profile).flush()

    fun destroy(view: WebView) {
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    fun asset(context: Context, name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
}
