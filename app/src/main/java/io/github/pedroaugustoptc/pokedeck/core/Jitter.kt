package io.github.pedroaugustoptc.pokedeck.core

import kotlin.random.Random

/** Todo intervalo do app passa por aqui: nada em ritmo exato, que é o que mais denuncia um robô. */
object Jitter {
    fun of(baseMs: Long, pct: Double, rng: Random = Random.Default): Long =
        maxOf(1L, (baseMs * (1 + (rng.nextDouble() * 2 - 1) * pct)).toLong())

    fun between(minMs: Long, maxMs: Long, rng: Random = Random.Default): Long =
        minMs + (rng.nextDouble() * (maxMs - minMs)).toLong()

    fun sec(s: Long, pct: Double = Timing.JITTER_PCT) = of(s * 1000, pct)
}
