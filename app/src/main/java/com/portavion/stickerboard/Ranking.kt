package com.portavion.stickerboard

enum class Move { Earlier, Later }

sealed interface Ranked<T> {
    val item: T
    data class Pinned<T>(
        override val item: T,
        val canMoveEarlier: Boolean,
        val canMoveLater: Boolean,
    ) : Ranked<T>
    data class Unpinned<T>(override val item: T) : Ranked<T>
}

class Ranking private constructor(
    val pins: List<String>,
    val recents: List<String>,
) {
    fun withPin(stem: String): Ranking = of(pins + stem, recents)

    fun withoutPin(stem: String): Ranking = of(pins.filter { it != stem }, recents)

    fun withPinMoved(stem: String, move: Move): Ranking {
        val i = pins.indexOf(stem)
        if (i < 0) return this
        val j = when (move) {
            Move.Earlier -> i - 1
            Move.Later -> i + 1
        }
        if (j !in pins.indices) return this
        val next = pins.toMutableList()
        val other = next[j]
        next[j] = next[i]
        next[i] = other
        return of(next, recents)
    }

    fun withSend(stem: String): Ranking =
        if (stem in pins) this
        else of(pins, listOf(stem) + recents.filter { it != stem })

    fun retaining(live: Set<String>): Ranking =
        of(pins.filter { it in live }, recents.filter { it in live })

    fun <T> arrange(items: List<T>, stem: (T) -> String): List<Ranked<T>> {
        val index = items.associateBy(stem)
        val pinItems = pins.mapNotNull { index[it] }
        val pinnedStems = pinItems.map(stem).toSet()
        val recentItems = recents.mapNotNull { s ->
            if (s in pinnedStems) null else index[s]
        }
        val placed = pinnedStems + recentItems.map(stem)
        val rest = items.filter { stem(it) !in placed }
        val lastPin = pinItems.lastIndex
        val rankedPins: List<Ranked<T>> = pinItems.mapIndexed { i, item ->
            Ranked.Pinned(
                item = item,
                canMoveEarlier = i > 0,
                canMoveLater = i < lastPin,
            )
        }
        val rankedRest: List<Ranked<T>> = (recentItems + rest).map { Ranked.Unpinned(it) }
        return rankedPins + rankedRest
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ranking) return false
        return pins == other.pins && recents == other.recents
    }

    override fun hashCode(): Int = 31 * pins.hashCode() + recents.hashCode()

    companion object {
        const val RECENTS_MAX = 24

        fun of(pins: List<String>, recents: List<String>): Ranking =
            Ranking(clean(pins), clean(recents).take(RECENTS_MAX))

        private fun clean(stems: List<String>): List<String> =
            stems.filter { it.isNotBlank() && STEM_SEP !in it }.distinct()
    }
}

internal const val STEM_SEP = "/"

internal fun joinStems(stems: List<String>): String = stems.joinToString(STEM_SEP)

internal fun splitStems(raw: String?): List<String> =
    if (raw.isNullOrEmpty()) emptyList() else raw.split(STEM_SEP)
