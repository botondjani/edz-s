package hu.edzes.player

import hu.edzes.model.Step

/** Az edzéslejátszó állapotgépe. Tiszta logika: az időt és a távot kívülről kapja. */
class Engine(steps: List<Step>) {
    sealed interface Event {
        data class Started(val step: Step) : Event
        data class Done(val step: Step, val completed: Boolean) : Event
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
        val limitMs = (current?.durationS ?: return emptyList()) * 1000L
        val leftBefore = secondsLeft(limitMs)
        stepMs += dtMs
        if (stepMs >= limitMs) return advance(true)
        val left = secondsLeft(limitMs)
        return if (left != leftBefore && left in 1..3) listOf(Event.Countdown(left)) else emptyList()
    }

    private fun secondsLeft(limitMs: Long) = ((limitMs - stepMs + 999) / 1000).toInt()

    /** GPS-ből mért megtett táv; csak táv alapú szakasznál számít. */
    fun addDistance(meters: Double): List<Event> {
        val goal = current?.distanceM ?: return emptyList()
        if (paused) return emptyList()
        stepDistM += meters
        return if (stepDistM >= goal) advance(true) else emptyList()
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
        val done = list[index]
        index++
        stepMs = 0; stepDistM = 0.0
        return listOf(Event.Done(done, completed), current?.let { Event.Started(it) } ?: Event.End)
    }
}
