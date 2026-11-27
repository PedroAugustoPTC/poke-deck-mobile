package io.github.pedroaugustoptc.pokedeck.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import io.github.pedroaugustoptc.pokedeck.R
import kotlin.math.roundToInt

/** Os tokens do design "Madeira & Noite" (design/README.md). */
object C {
    const val BG = 0xFF140A0F.toInt()
    const val PANEL = 0xFF1E1219.toInt()
    const val PANEL_HIGH = 0xFF2A141D.toInt()
    const val WELL = 0xFF33202B.toInt()
    const val THREAD = 0xFF5C3948.toInt()
    const val WOOD_DARK = 0xFF480E1E.toInt()
    const val WOOD = 0xFF914850.toInt()
    const val WOOD_MID = 0xFF722C3F.toInt()
    const val EDGE = 0xFFBD6951.toInt()
    const val LIGHT = 0xFFF1D3B7.toInt()
    const val HONEY = 0xFFE7A86D.toInt()
    const val GOLD = 0xFFE2A93F.toInt()
    const val GOLD_LIGHT = 0xFFFFE9A3.toInt()
    const val GOLD_DIM = 0xFF3D2C10.toInt()
    const val GOLD_SWITCH = 0xFFB98A2E.toInt()
    const val GOLD_EDGE = 0xFF7D5313.toInt()
    const val ON_GOLD = 0xFF3A0A16.toInt()
    const val TEXT = 0xFFFFF6EF.toInt()
    const val WEAK = 0xFFC9A9B4.toInt()
    const val TWITCH = 0xFFBF94FF.toInt()
    const val KICK = 0xFF53FC18.toInt()
    const val OK = 0xFF6FD08A.toInt()
    const val WARN = 0xFFF0A03C.toInt()
    const val BAD = 0xFFFFC9BD.toInt()
    const val DIM = 0xB8140A0F.toInt()
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** As fontes do design, embutidas (sem Google Fonts). */
object Fonts {
    lateinit var body: Typeface
    lateinit var bold: Typeface
    lateinit var semi: Typeface
    lateinit var cond: Typeface
    lateinit var pixel: Typeface

    fun load(c: Context) {
        if (::body.isInitialized) return
        body = ResourcesCompat.getFont(c, R.font.barlow_400)!!
        semi = ResourcesCompat.getFont(c, R.font.barlow_600)!!
        bold = ResourcesCompat.getFont(c, R.font.barlow_700)!!
        cond = ResourcesCompat.getFont(c, R.font.barlow_sc_500)!!
        pixel = ResourcesCompat.getFont(c, R.font.pixelify_600)!!
    }
}

fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).roundToInt()

fun Context.box(fill: Int, stroke: Int = 0, strokeDp: Int = 1, radiusDp: Number = 4) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radiusDp).toFloat()
    if (stroke != 0) setStroke(dp(strokeDp), stroke)
}

/** Fundo de botão de madeira: borda escura e o brilho de 1 px no topo. */
fun Context.woodBox(fill: Int = C.WOOD_MID, stroke: Int = C.WOOD_DARK): LayerDrawable {
    val shine = GradientDrawable().apply { setColor(0x38F1D3B7); cornerRadius = dp(4).toFloat() }
    return LayerDrawable(arrayOf(box(fill, stroke), shine)).apply {
        setLayerInset(1, dp(1), dp(1), dp(1), 0)
        setLayerHeight(1, dp(1))
    }
}

fun Context.text(s: CharSequence = "", size: Float = 14f, color: Int = C.TEXT, font: Typeface = Fonts.body): TextView =
    TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = font
        includeFontPadding = false
    }

fun Context.title(s: String) = text(s, 18f, C.HONEY, Fonts.pixel)

fun TextView.icon(res: Int, tint: Int = currentTextColor, sizeDp: Int = 16): TextView {
    val d = context.getDrawable(res)!!.mutate()
    d.setTint(tint)
    d.setBounds(0, 0, context.dp(sizeDp), context.dp(sizeDp))
    setCompoundDrawablesRelative(d, null, null, null)
    compoundDrawablePadding = context.dp(6)
    return this
}

fun Context.button(s: String, iconRes: Int? = null, minHeightDp: Int = 44, gold: Boolean = false, onClick: () -> Unit): TextView =
    text(s, 15f, if (gold) C.ON_GOLD else C.TEXT, Fonts.cond).apply {
        gravity = Gravity.CENTER
        minHeight = dp(minHeightDp)
        minimumHeight = dp(minHeightDp)
        setPadding(dp(12), 0, dp(12), 0)
        background = if (gold) woodBox(C.GOLD, C.GOLD_EDGE) else woodBox()
        iconRes?.let { icon(it) }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

fun Context.iconButton(res: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(C.TEXT)
    scaleType = ImageView.ScaleType.CENTER
    background = woodBox()
    contentDescription = desc
    minimumWidth = dp(44)
    minimumHeight = dp(44)
    setOnClickListener { onClick() }
}

fun Context.chip(s: String, color: Int, bg: Int = C.WELL, sizeSp: Float = 13f) = text(s, sizeSp, color).apply {
    gravity = Gravity.CENTER_VERTICAL
    minHeight = dp(24)
    setPadding(dp(7), dp(3), dp(7), dp(3))
    background = box(bg, radiusDp = 3)
}

/** Etiqueta liga/desliga (resgate T/K, recompra): dourada quando ligada. */
fun TextView.flag(on: Boolean) {
    setTextColor(if (on) C.GOLD_LIGHT else C.WEAK)
    background = context.box(if (on) C.GOLD_DIM else C.PANEL, if (on) C.GOLD else C.THREAD, radiusDp = 3)
    compoundDrawablesRelative[0]?.setTint(currentTextColor)
}

fun Context.field(value: String, numeric: Boolean = false, multiline: Boolean = false) = EditText(this).apply {
    setText(value)
    setTextColor(C.TEXT)
    setHintTextColor(C.WEAK)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    typeface = if (multiline) Typeface.MONOSPACE else Fonts.body
    background = box(C.BG, C.THREAD)
    setPadding(dp(10), dp(8), dp(10), dp(8))
    minHeight = dp(44)
    inputType = when {
        numeric -> InputType.TYPE_CLASS_NUMBER
        multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else -> InputType.TYPE_CLASS_TEXT
    }
    if (multiline) {
        gravity = Gravity.TOP
        minLines = 5
        isSingleLine = false
    } else isSingleLine = true
}

fun Context.column(gapDp: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gapDp > 0) {
        dividerDrawable = GradientDrawable().apply { setSize(0, dp(gapDp)) }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }
}

fun Context.row(gapDp: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gapDp > 0) {
        dividerDrawable = GradientDrawable().apply { setSize(dp(gapDp), 0) }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }
}

fun Context.spacer() = View(this)

fun <T : View> LinearLayout.add(v: T, width: Int = MATCH, height: Int = WRAP, weight: Float = 0f): T {
    addView(v, LinearLayout.LayoutParams(width, height, weight))
    return v
}

fun <T : View> FrameLayout.add(v: T, width: Int = MATCH, height: Int = MATCH, gravity: Int = Gravity.NO_GRAVITY): T {
    addView(v, FrameLayout.LayoutParams(width, height, gravity))
    return v
}

fun View.pad(h: Int, v: Int = h): View {
    setPadding(context.dp(h), context.dp(v), context.dp(h), context.dp(v))
    return this
}

/** Painel (fundo, borda e respiro), como as seções do design. */
fun Context.panel(fill: Int = C.PANEL_HIGH, stroke: Int = C.WOOD_DARK, gapDp: Int = 10) = column(gapDp).apply {
    background = box(fill, stroke)
    setPadding(dp(12), dp(12), dp(12), dp(12))
}

/** O interruptor do design: 46×28, dourado quando ligado. */
@SuppressLint("ViewConstructor")
class Toggle(context: Context, on: Boolean, private val changed: (Boolean) -> Unit) : View(context) {
    var on = on
        set(value) {
            field = value
            contentDescription = if (value) "ligado" else "desligado"
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = if (on) "ligado" else "desligado"
        setOnClickListener {
            this.on = !this.on
            changed(this.on)
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(46), context.dp(28))

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        r.set(d / 2, d / 2, width - d / 2, height - d / 2)
        paint.style = Paint.Style.FILL
        paint.color = if (on) C.GOLD_SWITCH else C.WELL
        c.drawRoundRect(r, 4 * d, 4 * d, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        paint.color = if (on) C.GOLD_EDGE else C.THREAD
        c.drawRoundRect(r, 4 * d, 4 * d, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (on) C.GOLD_LIGHT else C.WEAK
        val left = if (on) width - 24 * d else 4 * d
        r.set(left, 4 * d, left + 20 * d, 24 * d)
        c.drawRoundRect(r, 2 * d, 2 * d, paint)
    }
}

/** Linha "nome + nota … interruptor" das listas de ajustes. */
fun Context.switchRow(name: String, note: String?, on: Boolean, changed: (Boolean) -> Unit): Pair<LinearLayout, Toggle> {
    val row = row(10).apply { minimumHeight = dp(52) }
    val texts = column(1)
    texts.add(text(name, 15f, font = Fonts.semi))
    note?.let { texts.add(text(it, 13f, C.WEAK)) }
    row.add(texts, 0, WRAP, 1f)
    val t = Toggle(this, on, changed)
    row.add(t, WRAP, WRAP)
    return row to t
}

fun Context.divider() = View(this).apply { setBackgroundColor(C.WELL) }
