package io.github.pedroaugustoptc.pokedeck.svc

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

/**
 * Uma página escondida de um site (o player da Twitch ou da Kick, parado, mudo e sem imagens) no perfil da conta. Só
 * existe para rodar os pedidos de pontos DE DENTRO do site real: o fetch sai com os cookies, o Cloudflare e a checagem
 * anti-robô do próprio site. No PC eram duas por conta, sempre vivas; aqui é UMA por conta, que troca de site quando
 * precisa e some depois de 10 min sem uso ([Account] cuida disso).
 */
class AgentPage(context: Context, profile: String, private val onGone: () -> Unit) {
    private val view: WebView = Web.create(context, profile)
    private val lock = Mutex()
    private val pending = HashMap<Int, CompletableDeferred<String?>>()
    private var nextId = 1
    private var readyAt = Long.MAX_VALUE
    private var host: String? = null
    var usedAt = System.currentTimeMillis()
        private set

    init {
        view.settings.blockNetworkImage = true
        WebViewCompat.addWebMessageListener(view, "pdAgent", setOf("https://player.twitch.tv", "https://player.kick.com")) { _, message, _, _, _ ->
            try {
                val o = JSONObject(message.data ?: return@addWebMessageListener)
                pending.remove(o.optInt("id"))?.complete(if (o.isNull("r")) null else o.optString("r"))
            } catch (_: JSONException) {
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                readyAt = Long.MAX_VALUE
            }

            // O player carrega os scripts do site (inclusive o anti-robô) depois do load: dá um tempo antes do 1º pedido.
            override fun onPageFinished(v: WebView, url: String?) {
                if (readyAt == Long.MAX_VALUE) readyAt = System.currentTimeMillis() + 5_000
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) host = null // próxima vez navega de novo
            }

            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                destroy()
                onGone()
                return true
            }
        }
        Deck.parking.park(view)
    }

    /**
     * Roda [script] (uma expressão que dá uma Promise de texto) na página de [url] e devolve o texto; null se a página
     * não ficou pronta ou o script falhou. Um por vez.
     */
    suspend fun eval(url: String, script: String): String? = lock.withLock {
        usedAt = System.currentTimeMillis()
        val target = Uri.parse(url).host
        if (host != target) {
            host = target
            readyAt = Long.MAX_VALUE
            view.loadUrl(url)
        }
        val until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < readyAt) {
            if (System.currentTimeMillis() > until) {
                host = null
                return@withLock null
            }
            delay(500)
        }
        val id = nextId++
        val done = CompletableDeferred<String?>()
        pending[id] = done
        view.evaluateJavascript(
            "(function(){var id=$id;Promise.resolve().then(function(){return $script})" +
                ".then(function(r){pdAgent.postMessage(JSON.stringify({id:id,r:r}))}," +
                "function(){pdAgent.postMessage(JSON.stringify({id:id,r:null}))})})()", null)
        val result = withTimeoutOrNull(30_000) { done.await() }
        pending.remove(id)
        usedAt = System.currentTimeMillis()
        result
    }

    fun destroy() {
        pending.values.forEach { it.complete(null) }
        pending.clear()
        Web.destroy(view)
    }
}
