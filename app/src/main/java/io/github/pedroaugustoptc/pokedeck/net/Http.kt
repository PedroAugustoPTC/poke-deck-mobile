package io.github.pedroaugustoptc.pokedeck.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** O cliente HTTP de fora das páginas (lives, watchers, atualização). Um só para o app todo. */
object Http {
    /** O user-agent do WebView do aparelho (o app preenche ao abrir): os pedidos de fora dizem ser o mesmo navegador. */
    @Volatile
    var userAgent = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    class Res(val code: Int, val body: String) {
        val ok get() = code in 200..299
    }

    /** Manda o pedido (fora da thread principal) e lê o corpo inteiro. Erros de rede viram IOException. */
    suspend fun send(req: Request.Builder, readBody: Boolean = true, ua: String = userAgent): Res = runInterruptible(Dispatchers.IO) {
        client.newCall(req.header("User-Agent", ua).build()).execute().use { r: Response ->
            Res(r.code, if (readBody) r.body?.string() ?: "" else "")
        }
    }

    fun get(url: String): Request.Builder = Request.Builder().url(url)
}

/** Um websocket do OkHttp visto como corrotina: mensagens num canal, abertura e fechamento como Deferred. */
class Ws private constructor() : WebSocketListener() {
    val incoming = Channel<String>(Channel.UNLIMITED)
    val closed = CompletableDeferred<String?>()
    private val opened = CompletableDeferred<Unit>()
    private lateinit var socket: WebSocket

    fun send(text: String) = socket.send(text)
    fun close() = socket.close(1000, null)
    fun abort() = socket.cancel()

    override fun onOpen(webSocket: WebSocket, response: Response) {
        opened.complete(Unit)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        incoming.trySend(text)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        finish(null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(null)

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        opened.completeExceptionally(t)
        finish(t.message ?: "conexão caiu")
    }

    private fun finish(error: String?) {
        closed.complete(error)
        incoming.close()
    }

    companion object {
        suspend fun connect(url: String, vararg headers: Pair<String, String>): Ws {
            val ws = Ws()
            val req = Request.Builder().url(url).header("User-Agent", Http.userAgent)
            headers.forEach { (k, v) -> req.header(k, v) }
            ws.socket = Http.client.newWebSocket(req.build(), ws)
            try {
                withTimeout(15_000) { ws.opened.await() }
            } catch (e: Throwable) {
                ws.abort()
                throw e
            }
            return ws
        }
    }
}
