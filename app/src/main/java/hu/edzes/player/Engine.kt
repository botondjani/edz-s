package hu.edzes.player

import hu.edzes.model.Step

/** Az edzéslejátszó állapotgépe. Tiszta logika: az időt és a távot kívülről kapja. */
class Engine(steps: List<Step>) {
    sealed interface Event {
        data class Started(val step: Step) : Event
        /** ms / distM: a lépésen ténylegesen töltött idő és megtett táv. */
        data class Done(val step: Step, val completed: Boolean, val ms: Long = 0, val distM: Double = 0.0) : Event
        data class Countdown(val secondsLeft: Int) : Event
        data object End : Event
    }

    private val list = steps.toMutableList()
    val steps: List<Step> get() = list
    var index = 0; private set
    var stepMs = 0L; private set
    var totalMs = 0L; private set
    var stepDistM = 0.0; private set
    var paused = false

    val current get() = list.getOrNull(index)
    val next get() = list.getOrNull(index + 1)

    fun tick(dtMs: Long): List<Event> {
        if (paused) return emptyList()
        totalMs += dtMs
        val step = current ?: return emptyList()
        val limitMs = step.durationS?.times(1000L)
        val leftBefore = limitMs?.let(::secondsLeft)
        stepMs += dtMs
        if (limitMs == null) return emptyList()
        if (stepMs >= limitMs) return advance(true)
        val left = secondsLeft(limitMs)
        return if (left != leftBefore && left in 1..3) listOf(Event.Countdown(left)) else emptyList()
    }

    private fun secondsLeft(limitMs: Long) = ((limitMs - stepMs + 999) / 1000).toInt()

    /** GPS-ből mért megtett táv; táv alapú szakaszt a cél elérésekor léptet. */
    fun addDistance(meters: Double): List<Event> {
        if (paused || current == null) return emptyList()
        stepDistM += meters
        val goal = current?.distanceM ?: return emptyList()
        return if (stepDistM >= goal) advance(true) else emptyList()
    }

    /** A hátralévő idő, ha minden hátralévő lépés időre megy; különben null. */
    fun remainingMs(): Long? {
        val rest = list.drop(index)
        if (rest.isEmpty() || rest.any { it.durationS == null }) return null
        return rest.sumOf { it.durationS!! * 1000L } - stepMs
    }

    /** Kézi léptetés: sorozat kész, vagy időzített lépés átugrása. */
    fun next(): List<Event> = if (current == null) emptyList() else advance(current!!.manual)

    fun prev(): List<Event> {
        if (index == 0) return emptyList()
        index--
        stepMs = 0; stepDistM = 0.0
        return listOf(Event.Started(list[index]))
    }

    fun append(more: List<Step>): List<Event> {
        val wasAtEnd = current == null
        list += more
        return if (wasAtEnd && more.isNotEmpty()) listOf(Event.Started(list[index])) else emptyList()
    }

    private fun advance(completed: Boolean): List<Event> {
        val done = Event.Done(list[index], completed, stepMs, stepDistM)
        index++
        stepMs = 0; stepDistM = 0.0
        return listOf(done, current?.let { Event.Started(it) } ?: Event.End)
    }
}
