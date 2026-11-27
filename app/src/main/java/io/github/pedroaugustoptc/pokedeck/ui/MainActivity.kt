package io.github.pedroaugustoptc.pokedeck.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.pedroaugustoptc.pokedeck.core.Release
import io.github.pedroaugustoptc.pokedeck.svc.Deck
import io.github.pedroaugustoptc.pokedeck.svc.Installer
import io.github.pedroaugustoptc.pokedeck.svc.Web
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/** Uma tela empilhada na [MainActivity]. */
abstract class Screen(val act: MainActivity) {
    abstract val view: View
    /** A cada segundo, com a tela visível. */
    open fun refresh() {}
    open fun resume() {}
    open fun close() {}
}

/**
 * A única Activity: empilha as telas (Menu embaixo; Conta, Config, Registro, Login por cima). As contas não moram aqui
 * ([Deck]); fechar a tela não para nada. Sempre retrato, ponta a ponta com os insets aplicados numa raiz só, igual do
 * Android 8 ao 16.
 */
class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private val stack = ArrayList<Screen>()
    private val ticker = Handler(Looper.getMainLooper())
    private var resumed = false
    private var pendingUpdate: Release? = null
    lateinit var menu: MenuScreen
        private set

    private val tick = object : Runnable {
        override fun run() {
            stack.lastOrNull()?.refresh()
            if (resumed) ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.load(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        content = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(content)

        Deck.init(this)
        if (!Web.supported()) {
            content.addView(webViewMissing())
            return
        }
        // Sem a permissão de sobrepor, os navegadores escondidos ficam neste canto de 1 px enquanto a tela existe.
        val corner = FrameLayout(this)
        content.addView(corner, FrameLayout.LayoutParams(1, 1))
        Deck.parking.fallback = corner
        Deck.sync()

        menu = MenuScreen(this)
        push(menu)
        if (!Deck.config.setupDone) push(SetupScreen(this, firstRun = true))
        else if (!Deck.config.tourDone) menu.startTour()
        Deck.scope.launch {
            delay(3_000)
            Installer.check()?.let { offerUpdate(it) }
        }
    }

    fun push(s: Screen) {
        stack.lastOrNull()?.view?.visibility = View.GONE
        stack += s
        content.addView(s.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        s.resume()
        s.refresh()
    }

    fun pop() {
        if (stack.size <= 1) return
        val s = stack.removeAt(stack.size - 1)
        s.close()
        content.removeView(s.view)
        stack.last().view.visibility = View.VISIBLE
        stack.last().resume()
        stack.last().refresh()
    }

    @Deprecated("targetSdk 35: ainda chamado em todas as versões")
    override fun onBackPressed() {
        if (stack.size > 1) pop() else moveTaskToBack(true) // o app segue rodando; só a tela sai da frente
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (Web.supported()) Deck.parking.repark() // a permissão de sobrepor pode ter chegado agora
        stack.lastOrNull()?.resume()
        ticker.post(tick)
        pendingUpdate?.let { if (packageManager.canRequestPackageInstalls()) offerUpdate(it) }
    }

    override fun onPause() {
        resumed = false
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        stack.asReversed().forEach { it.close() }
        if (Web.supported()) {
            Deck.hide()
            Deck.parking.fallback = null
        }
        super.onDestroy()
    }

    // ---------------- atualização ----------------

    private fun offerUpdate(r: Release) {
        pendingUpdate = null
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Nova versão ${r.versionName}")
            .setMessage(r.notes.trim().take(1500).ifEmpty { "Sem notas." } +
                "\n\nO PokeDeck baixa e instala a versão nova. Logins e configurações ficam.")
            .setPositiveButton("Atualizar") { _, _ -> update(r) }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun update(r: Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            // Primeira vez: o Android pede para permitir instalar desta fonte; ao voltar, oferece de novo.
            pendingUpdate = r
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val progress = AlertDialog.Builder(this).setTitle("Baixando ${r.versionName}").setMessage("0%").setCancelable(false).show()
        Deck.scope.launch {
            try {
                val file = Installer.download(this@MainActivity, r) { pct -> runOnUiThread { progress.setMessage("$pct%") } }
                progress.dismiss()
                Installer.install(this@MainActivity, file)
            } catch (e: IOException) {
                progress.dismiss()
                AlertDialog.Builder(this@MainActivity).setTitle("Não deu para atualizar").setMessage(e.message ?: "erro de rede")
                    .setPositiveButton("Ok", null).show()
            }
        }
    }

    private fun webViewMissing(): View {
        val col = column(16).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        col.add(title("Atualize o navegador do Android"))
        col.add(text("O PokeDeck usa o \"Android System WebView\" para rodar o jogo, com uma conta separada da outra. " +
            "O deste aparelho é antigo demais para isso. Atualize-o na Play Store e abra o app de novo.", 15f, C.LIGHT))
        col.add(button("Abrir na Play Store") {
            val id = "com.google.android.webview"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")))
            }
        })
        return col
    }
}
