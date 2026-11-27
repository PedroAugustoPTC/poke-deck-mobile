package io.github.pedroaugustoptc.pokedeck.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * A recompra de uma conta (porte 1:1 do Restock.cs do PC). Abaixo de "below", começa um lote de "buy", uma vez; o jogo
 * aceita até 9.999 por pedido, então o lote sai em pedidos com intervalo aleatório. Sem ouro para o lote inteiro,
 * espera juntar; a exceção é poção ou revive zerado, que compra o que o ouro deixar. Prioridade: poção, revive,
 * pokebola. [tick] devolve as mensagens para o socket do jogo.
 */
class Restock(private val now: () -> Long = System::currentTimeMillis, private val rng: Random = Random.Default) {
    companion object {
        const val MAX_QTY = 9999L
        private const val TIMEOUT = 15_000L
        private const val REFUSED_WAIT = 30_000L
        private const val FAIL_PAUSE = 120_000L
        private const val AUTO_REPEAT = 30_000L
        private const val WAIT = "wait"
        private val fmt = NumberFormat.getIntegerInstance(Locale("pt", "BR"))
        private fun fmt(n: Double) = fmt.format(n.roundToLong())
        private fun fmt(n: Long) = fmt.format(n)
    }

    private class Item(val id: String, val name: String, var price: Double, val heal: Double = 0.0, val healPct: Double = 0.0) {
        var cost = Double.POSITIVE_INFINITY
        var useful = 0.0
    }

    private class Lot(val item: Item, var left: Long)
    private data class Pending(val kind: String, val item: Item, val qty: Long, val start: Long, val at: Long)

    private val lots = linkedMapOf<String, Lot?>("potions" to null, "revives" to null, "balls" to null)
    private val paid = HashMap<String, Double>()
    private val once = HashSet<String>()
    private var pending: Pending? = null
    private var nextAt = 0L
    private var pauseUntil = 0L
    private var failures = 0
    private var lastAutoSig = ""
    private var lastAutoAt = 0L
    private var config = RestockConfig()

    var note: ((String) -> Unit)? = null
    val paused get() = pauseUntil > now()
    var potionPick: String? = null
        private set

    /** Recebe sempre uma cópia (a config viva muda pela tela). */
    fun configure(c: RestockConfig) {
        for (kind in lots.keys.toList())
            if (!c.on || ruleOf(c, kind) != ruleOf(config, kind)) lots[kind] = null
        if (c.on != config.on) say(if (c.on) "recompra ligada" else "recompra desligada")
        config = c
        once.clear()
        pauseUntil = 0
        failures = 0
    }

    private fun ruleOf(c: RestockConfig, kind: String) = when (kind) {
        "potions" -> c.potions
        "revives" -> c.revives
        else -> c.balls
    }

    private fun say(text: String) = note?.invoke(text)
    private fun sayOnce(key: String, text: String) {
        if (once.add(key)) say(text)
    }

    private fun shop(s: GameState, category: String) =
        s.shop.filter { it.category == category && it.price > 0 }
            .map { Item(it.id, it.name, paid[it.id] ?: it.price, it.heal, it.healPct) }
            .sortedBy { it.price }

    private fun total(s: GameState, items: List<Item>) = items.sumOf { s.have(it.id) }

    private fun threshold(s: GameState): Double {
        val v = s.automation?.opt("hpLimiar")
        return ((v as? Number)?.toDouble() ?: 0.3).coerceIn(0.1, 1.0)
    }

    /** Custo-benefício: ouro por HP que a poção cura de verdade no ativo (a automação usa abaixo do limiar). */
    private fun pickPotion(s: GameState, potions: List<Item>): Item? {
        if (config.potions.id > 0) return potions.firstOrNull { it.id == config.potions.id.toString() }
        val hp = s.maxHp ?: return null
        val room = max(0.1, 1 - threshold(s)) * hp
        for (p in potions) {
            p.useful = min(if (p.healPct > 0) p.healPct * hp else p.heal, room)
            p.cost = if (p.useful > 0) p.price / p.useful else Double.POSITIVE_INFINITY
        }
        return potions.filter { it.cost < Double.POSITIVE_INFINITY }
            .sortedWith(compareBy<Item> { it.cost }.thenByDescending { it.useful }).firstOrNull()
    }

    private fun stock(s: GameState, kind: String, item: Item) = if (kind == "balls") s.balls[item.id] ?: 0L else s.have(item.id)

    private fun plan(s: GameState, kind: String, rule: Rule, count: Long, item: Item?, waiting: String?): Any? {
        if (!rule.on || rule.buy <= 0) {
            lots[kind] = null
            return null
        }
        val spare = (s.gold - config.goldFloor).toDouble()
        var lot = lots[kind]
        if (lot == null) {
            if (count >= rule.below || item == null || item.price <= 0) return null
            var qty = rule.buy.toLong()
            if (waiting != null || spare < rule.buy * item.price) {
                if (kind == "balls" || count >= 1) {
                    sayOnce("ouro:$kind", if (waiting != null)
                        "${item.name}: abaixo de ${fmt(rule.below.toLong())}, esperando $waiting (o ouro vai primeiro para lá)"
                    else
                        "${item.name}: abaixo de ${fmt(rule.below.toLong())}, esperando juntar ${fmt(config.goldFloor + rule.buy * item.price)} de ouro")
                    return WAIT
                }
                qty = min(rule.buy.toLong(), floor(spare / item.price).toLong())
                if (qty < 1) {
                    sayOnce("ouro:$kind", "${item.name}: acabou e o ouro está no mínimo (${fmt(config.goldFloor)})")
                    return WAIT
                }
            }
            once.remove("ouro:$kind")
            lot = Lot(item, qty)
            lots[kind] = lot
            say("${item.name}: ${fmt(count)} na mochila, abaixo de ${fmt(rule.below.toLong())}: compra ${fmt(qty)}")
        }
        val q = minOf(MAX_QTY, lot.left, floor(spare / lot.item.price).toLong())
        if (q < 1) {
            lots[kind] = null
            say("${lot.item.name}: o ouro chegou no mínimo no meio do lote; ficaram ${fmt(lot.left)} sem comprar")
            return null
        }
        return Pending(kind, lot.item, q, stock(s, kind, lot.item), 0)
    }

    /** Uma volta (a cada retrato do jogo, ~1,5 s). Devolve as mensagens para mandar no socket do jogo (0, 1 ou 2). */
    fun tick(s: GameState): List<String> {
        val send = ArrayList<String>()
        if (!config.on || !s.connected || !s.shopLoaded) return send
        val now = now()
        val potions = shop(s, "heal")
        val revives = shop(s, "revive")
        val potion = pickPotion(s, potions)
        potionPick = potion?.name
        fixAutomation(s, potion, potions, revives, now)?.let { send += it }
        pending?.let { p ->
            when {
                stock(s, p.kind, p.item) - p.start >= p.qty / 2.0 -> done("comprou ${p.item.name} ×${fmt(p.qty)}")
                now - p.at < TIMEOUT -> return send
                else -> failed("${p.item.name}: o jogo não respondeu à compra")
            }
        }
        if (now < nextAt || now < pauseUntil) return send

        val b = s.ballCatalog.firstOrNull { it.id == config.balls.id && it.buyable && it.price > 0 }
        val ball = b?.let { Item(it.id.toString(), it.name, paid["bola${it.id}"] ?: it.price.toDouble()) }
        var waiting: String? = null
        val steps = listOf(
            listOf("potions", total(s, potions), potion, "as poções"),
            listOf("revives", total(s, revives), revives.firstOrNull(), "os revives"),
            listOf("balls", if (ball == null) 0L else s.balls[ball.id] ?: 0L, ball, "a pokebola"),
        )
        for ((kind, count, item, name) in steps) {
            kind as String
            when (val step = plan(s, kind, ruleOf(config, kind), count as Long, item as Item?, waiting)) {
                WAIT -> if (waiting == null) waiting = name as String
                is Pending -> {
                    pending = step.copy(at = now)
                    nextAt = now + Jitter.between(Timing.BUY_GAP_MIN_SEC * 1000, Timing.BUY_GAP_MAX_SEC * 1000, rng)
                    send += JSONObject()
                        .put("t", "shop.buy")
                        .put("kind", if (kind == "balls") "ball" else "item")
                        .put("id", step.item.id.toIntOrNull() ?: step.item.id)
                        .put("qty", step.qty)
                        .toString()
                    break
                }
            }
        }
        return send
    }

    /** As Automações do jogo para o que a recompra compra (um "auto.set" igual ao do painel do jogo). */
    private fun fixAutomation(s: GameState, potion: Item?, potions: List<Item>, revives: List<Item>, now: Long): String? {
        val auto = s.automation ?: return null
        val patch = JSONObject()
        val turned = ArrayList<String>()
        fun ids(key: String): List<Any> = auto.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.get(it) } } ?: emptyList()
        fun key(n: Any?) = n?.toString() ?: ""
        fun id(id: String): Any = id.toIntOrNull() ?: id
        fun isTrue(k: String) = auto.opt(k) == true

        if (config.potions.on) {
            if (!isTrue("autoPotion")) {
                patch.put("autoPotion", true)
                turned += "Usar Poções"
            }
            val queue = ids("potionIds")
            if (config.potionFirst && potion != null && key(queue.firstOrNull()) != potion.id) {
                val rest = if (queue.isNotEmpty()) queue.filter { key(it) != potion.id }
                else potions.filter { it.id != potion.id }.sortedByDescending { it.price }.map { id(it.id) }
                patch.put("potionIds", JSONArray(listOf(id(potion.id)) + rest))
                turned += "${potion.name} em 1º no \"Usar Poções\""
            }
        }
        if (config.revives.on) {
            if (!isTrue("autoRevive")) {
                patch.put("autoRevive", true)
                turned += "Usar Revive"
            }
            val chosen = ids("reviveIds")
            val r = revives.firstOrNull()
            if (r != null && chosen.isNotEmpty() && chosen.all { key(it) != r.id }) {
                patch.put("reviveIds", JSONArray(chosen + id(r.id)))
                turned += "${r.name} no \"Usar Revive\""
            }
        }
        val ballId = config.balls.id
        if (config.balls.on && ballId > 0 && s.vip) {
            if (!isTrue("autoBallSemParar") || isTrue("autoBallAteCapturar")) {
                patch.put("autoBallSemParar", true)
                patch.put("autoBallAteCapturar", false)
                turned += "Lançar sem parar"
            }
            val balls = ids("ballIds")
            if (key(balls.firstOrNull()) != ballId.toString()) {
                patch.put("ballIds", JSONArray(listOf<Any>(ballId) + balls.filter { key(it) != ballId.toString() }))
                turned += "pokebola da recompra em 1º"
            }
        }
        if (turned.isEmpty()) return null
        val sig = patch.toString()
        if (sig == lastAutoSig && now < lastAutoAt + AUTO_REPEAT) return null
        lastAutoSig = sig
        lastAutoAt = now
        val message = JSONObject(auto.toString())
        for (k in patch.keys()) message.put(k, patch.get(k))
        message.put("t", "auto.set")
        say("Automações do jogo: " + turned.joinToString(", "))
        return message.toString()
    }

    /** O jogo avisou uma compra (evento "compra" da batalha): qtd e o ouro gasto. */
    fun bought(qty: Long, spent: Long) {
        val p = pending ?: return
        if (qty != p.qty) return // compra feita na mão
        if (spent > 0) {
            paid[if (p.kind == "balls") "bola" + p.item.id else p.item.id] = spent.toDouble() / p.qty
            p.item.price = spent.toDouble() / p.qty
        }
        done("comprou ${p.item.name} ×${fmt(p.qty)}" + if (spent > 0) " (−${fmt(spent)} ouro)" else "")
    }

    fun refused(reason: String) {
        val p = pending ?: return
        if (now() - p.at > 5_000) return
        failed("${p.item.name}: o jogo recusou a compra ($reason)")
        nextAt = now() + REFUSED_WAIT
    }

    private fun done(text: String) {
        val p = pending!!
        val lot = lots[p.kind]
        var rest = ""
        if (lot != null && lot.item === p.item) {
            lot.left -= p.qty
            if (lot.left > 0) rest = " (faltam ${fmt(lot.left)} do lote)" else lots[p.kind] = null
        }
        say(text + rest)
        pending = null
        failures = 0
    }

    private fun failed(text: String) {
        say(text)
        pending = null
        if (++failures >= 3) {
            failures = 0
            pauseUntil = now() + FAIL_PAUSE
            say("3 compras seguidas sem dar certo: pausa de 2 min")
        }
    }
}
