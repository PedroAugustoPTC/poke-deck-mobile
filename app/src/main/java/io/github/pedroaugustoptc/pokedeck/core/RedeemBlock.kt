package io.github.pedroaugustoptc.pokedeck.core

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Horário de bloqueio do resgate ("HH:mm"): se o buff que a conta já tem passa desse horário, resgatar mais não serve
 * para nada, e o resgate daquele site é desligado.
 */
object RedeemBlock {
    private val HHMM = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    /** A próxima vez que o relógio marca [block] a partir de [now]; null = sem bloqueio. */
    fun next(block: String?, now: LocalDateTime): LocalDateTime? {
        val m = HHMM.matchEntire(block ?: return null) ?: return null
        val at = now.toLocalDate().atTime(LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()))
        return if (at > now) at else at.plusDays(1)
    }

    /** O buff que falta ([leftSec]) já chega ao próximo horário de bloqueio. */
    fun covers(block: String?, leftSec: Int, now: LocalDateTime): Boolean {
        val at = next(block, now) ?: return false
        return now.plusSeconds(leftSec.toLong()) >= at
    }
}
