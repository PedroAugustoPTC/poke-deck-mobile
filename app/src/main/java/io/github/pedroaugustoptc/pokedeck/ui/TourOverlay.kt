package io.github.pedroaugustoptc.pokedeck.ui

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * O tour de 7 passos (design/screens/Tour.dc.html): escurece o menu e recorta em volta da parte explicada. A posição vem
 * da própria view (não "a olho"), então o contorno cai certo em qualquer tamanho de tela e fonte.
 */
@SuppressLint("ViewConstructor")
class TourOverlay(private val act: MainActivity, private val menu: MenuScreen, private val scroll: ScrollView, private val done: () -> Unit) : FrameLayout(act) {
    private class Step(val title: String, val text: String, val target: (() -> View)?)

    private val steps = listOf(
        Step("Bem-vindo ao PokeDeck", "Um tour rápido pelo app. Dá para pular agora e rever quando quiser em Config › Rever tour.", null),
        Step("Suas contas", "As contas entram sozinhas ao abrir o app. Cada barra mostra o estado, o ouro, os bônus T/K, as lives abertas e se o resgate e a recompra estão ligados.") { menu.accountsSection },
        Step("Abrir uma conta", "Toque na barra para ver o jogo da conta. Para voltar, toque em Menu no canto de cima.") { menu.firstBar },
        Step("Lives", "O que cada conta assiste, sem vídeo: só os pontos. \"Conferir\" olha agora quem está no ar.") { menu.livesSection },
        Step("Ajustes rápidos", "Economia, Mudo e ULTRA para todas as contas de uma vez, e o horário de bloqueio do resgate.") { menu.quickSection },
        Step("Config", "Contas, recompra, canais, resgates e \"Deixar rodando\", para o Android não fechar o app.") { menu.configBtn },
        Step("Pronto!", "Quando quiser relembrar algo, toque aqui ou em Config › Rever tour.") { menu.tourBtn },
    )
    private var at = 0
    private val hole = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val card = act.panel(C.PANEL_HIGH, C.GOLD)
    private val heading = act.title("")
    private val body = act.text("", 15f, C.LIGHT)
    private val counter = act.text("", 13f, C.WEAK)
    private val prev: TextView
    private val next: TextView

    init {
        setWillNotDraw(false)
        isClickable = true // o toque não passa para o menu embaixo
        card.add(heading)
        card.add(body)
        val buttons = card.add(act.row(8))
        buttons.add(counter, 0, WRAP, 1f)
        buttons.add(act.button("Pular") { done() }, WRAP, WRAP)
        prev = buttons.add(act.button("Anterior") { go(at - 1) }, WRAP, WRAP)
        next = buttons.add(act.button("Próximo", gold = true) { if (at == steps.size - 1) done() else go(at + 1) }, WRAP, WRAP)
        add(card, MATCH, WRAP, Gravity.TOP)
        (card.layoutParams as FrameLayout.LayoutParams).setMargins(act.dp(12), 0, act.dp(12), 0)
        go(0)
    }

    private fun go(i: Int) {
        at = i.coerceIn(0, steps.size - 1)
        val s = steps[at]
        heading.text = s.title
        body.text = s.text
        counter.text = "${at + 1} de ${steps.size}"
        prev.isEnabled = at > 0
        prev.alpha = if (at > 0) 1f else 0.4f
        next.text = if (at == steps.size - 1) "Concluir" else "Próximo"
        s.target?.invoke()?.let { t ->
            val tl = IntArray(2).also { t.getLocationInWindow(it) }
            val page = IntArray(2).also { scroll.getChildAt(0).getLocationInWindow(it) }
            scroll.smoothScrollTo(0, maxOf(0, tl[1] - page[1] - act.dp(80)))
        }
        postDelayed({ place() }, 350) // depois da rolagem
        place()
    }

    /** Recalcula o recorte e onde fica o cartão (também a cada segundo, se o menu mudar de tamanho). */
    fun place() {
        val t = steps[at].target?.invoke()
        if (t == null || !t.isShown) hole.setEmpty()
        else {
            val me = IntArray(2).also { getLocationInWindow(it) }
            val tl = IntArray(2).also { t.getLocationInWindow(it) }
            val pad = act.dp(4).toFloat()
            hole.set(tl[0] - me[0] - pad, tl[1] - me[1] - pad, tl[0] - me[0] + t.width + pad, tl[1] - me[1] + t.height + pad)
        }
        val lp = card.layoutParams as FrameLayout.LayoutParams
        val y = when {
            hole.isEmpty -> (height - card.height) / 2f
            hole.bottom + card.height + act.dp(12) < height -> hole.bottom + act.dp(12)
            else -> maxOf(act.dp(12).toFloat(), hole.top - card.height - act.dp(12))
        }
        if (lp.topMargin != y.toInt()) {
            lp.topMargin = y.toInt()
            card.layoutParams = lp
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        post { place() }
    }

    override fun onDraw(c: Canvas) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        val r = act.dp(4).toFloat()
        if (!hole.isEmpty) path.addRoundRect(hole, r, r, Path.Direction.CW)
        paint.style = Paint.Style.FILL
        paint.color = C.DIM
        c.drawPath(path, paint)
        if (!hole.isEmpty) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = act.dp(2).toFloat()
            paint.color = C.GOLD
            c.drawRoundRect(hole, r, r, paint)
        }
    }
}
