package io.github.pedroaugustoptc.pokedeck.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.ScrollView
import io.github.pedroaugustoptc.pokedeck.svc.Deck

/**
 * "Deixar rodando": o que o Android (e cada fabricante) precisa para não fechar o app com a tela apagada. É o que faltava
 * nos MVPs antigos, em que o jogo parava e a sessão se perdia até abrir o app de novo.
 */
class SetupScreen(act: MainActivity, private val firstRun: Boolean) : Screen(act) {
    override val view = act.column()
    private val items = act.column(12)
    private val brand = Build.MANUFACTURER.lowercase()

    private class Item(val title: String, val note: String, val done: () -> Boolean?, val action: String, val run: () -> Unit)

    private val list: List<Item> = listOfNotNull(
        if (Build.VERSION.SDK_INT >= 33) Item("Notificações", "Mostra \"N contas jogando\" e o botão Parar. Sem ela o Android esconde o aviso.",
            { act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }, "Permitir") {
            act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else null,
        Item("Sobrepor a outros apps", "Uma janela invisível de 1 ponto onde os jogos rodam com o app fechado. Sem ela, o Android pausa o jogo e a sessão pode cair.",
            { Settings.canDrawOverlays(act) }, "Abrir") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")))
        },
        Item("Bateria sem restrição", "O Android não coloca o app para dormir nem o impede de voltar sozinho se for fechado.",
            { (act.getSystemService(PowerManager::class.java)).isIgnoringBatteryOptimizations(act.packageName) }, "Permitir") {
            @SuppressLint("BatteryLife") // é exatamente o caso de uso: o app precisa rodar o tempo todo
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}"))
            open(i)
        },
        vendor(),
    )

    /** O passo extra de cada fabricante (não dá para conferir sozinho: o usuário marca). */
    private fun vendor(): Item? = when {
        brand in setOf("xiaomi", "redmi", "poco") -> Item("Xiaomi: início automático",
            "Ative \"Início automático\" para o PokeDeck. Depois, em Economia de bateria do app, escolha \"Sem restrições\". Nos Recentes, segure o app e toque no cadeado.",
            { null }, "Abrir") {
            open(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
        }
        brand == "samsung" -> Item("Samsung: apps nunca suspensos",
            "Configurações › Bateria › Limites de uso em segundo plano › Apps nunca suspensos: adicione o PokeDeck. Na tela do app, Bateria: \"Sem restrições\".",
            { null }, "Abrir app") { appDetails() }
        brand == "motorola" -> Item("Motorola: bateria do app",
            "Na tela do app, Bateria: \"Sem restrições\" (ou \"Não otimizar\").", { null }, "Abrir app") { appDetails() }
        else -> Item("Bateria do app", "Na tela do app, deixe a bateria \"Sem restrições\" se o aparelho tiver essa opção.", { null }, "Abrir app") { appDetails() }
    }

    private fun appDetails() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}")))

    private fun open(i: Intent) {
        try {
            act.startActivity(i)
        } catch (_: Exception) {
            appDetails() // a tela do fabricante não existe nesta versão: cai na tela do app
        }
    }

    init {
        with(act) {
            val header = view.add(row(8))
            header.setPadding(dp(14), dp(12), dp(10), dp(12))
            header.background = box(C.WOOD, C.WOOD_DARK, radiusDp = 0)
            header.add(text("Deixar rodando", 22f, C.GOLD_LIGHT, Fonts.pixel), 0, WRAP, 1f)
            val scroll = view.add(ScrollView(act), MATCH, 0, 1f)
            val page = column(14)
            page.setPadding(dp(12), dp(14), dp(12), dp(24))
            scroll.addView(page)
            page.add(text("Para as contas seguirem jogando e assistindo com o app fechado e a tela apagada, o Android precisa destas permissões. " +
                "Toque em cada uma; o app confere quando você voltar.", 15f, C.LIGHT))
            page.add(items)
            val foot = view.add(row(8))
            foot.setPadding(dp(12), dp(8), dp(12), dp(8))
            foot.setBackgroundColor(C.PANEL)
            foot.add(button(if (firstRun) "Continuar" else "Pronto", minHeightDp = 48, gold = true) { act.pop() }, 0, WRAP, 1f)
        }
    }

    override fun resume() = build()

    private fun build() {
        items.removeAllViews()
        for (it in list) {
            val done = it.done()
            val card = items.add(act.panel(C.PANEL, if (done == true) C.OK else C.THREAD, 6))
            val h = card.add(act.row(8))
            h.add(act.text(it.title, 16f, font = Fonts.bold), 0, WRAP, 1f)
            h.add(act.text(when (done) { true -> "ok"; false -> "falta"; null -> "confira" }, 13f,
                when (done) { true -> C.OK; false -> C.WARN; null -> C.WEAK }), WRAP, WRAP)
            card.add(act.text(it.note, 14f, C.LIGHT))
            if (done != true) card.add(act.button(it.action, onClick = it.run), WRAP, WRAP)
        }
        if (Deck.ready) Deck.parking.repark()
    }

    override fun close() {
        if (!firstRun || act.isFinishing) return // fechar o app no meio não conta como visto
        Deck.edit { it.setupDone = true }
        if (!Deck.config.tourDone) act.menu.startTour()
    }
}
