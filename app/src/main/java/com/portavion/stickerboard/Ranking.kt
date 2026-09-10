package com.portavion.stickerboard

enum class Move { Earlier, Later }

enum class Shelf { Favourites, Recents, Library }

sealed interface Ranked<T : Any> {
    val item: T
    val pinned: Boolean

    data class Favourite<T : Any>(
        override val item: T,
        val canMoveEarlier: Boolean,
        val canMoveLater: Boolean,
    ) : Ranked<T> {
        override val pinned: Boolean get() = true
    }

    data class Recent<T : Any>(
        override val item: T,
        override val pinned: Boolean,
    ) : Ranked<T>
}

@ConsistentCopyVisibility
data class Shelves<T : Any> internal constructor(
    val favourites: List<Ranked.Favourite<T>>,
    val recents: List<Ranked.Recent<T>>,
    val library: List<Ranked.Recent<T>>,
) {
    operator fun get(shelf: Shelf): List<Ranked<T>> = when (shelf) {
        Shelf.Favourites -> favourites
        Shelf.Recents -> recents
        Shelf.Library -> library
    }

    val libraryEmpty: Boolean get() = library.isEmpty()

    companion object {
        fun <T : Any> empty(): Shelves<T> = Shelves(emptyList(), emptyList(), emptyList())
    }
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
        of(pins, listOf(stem) + recents.filter { it != stem })

    fun retaining(live: Set<String>): Ranking =
        of(pins.filter { it in live }, recents.filter { it in live })

    fun <T : Any> shelves(items: List<T>, stem: (T) -> String): Shelves<T> {
        val index = items.associateBy(stem)
        val pinItems = pins.mapNotNull { index[it] }
        val lastPin = pinItems.lastIndex
        val favourites = pinItems.mapIndexed { i, item ->
            Ranked.Favourite(item, i > 0, i < lastPin)
        }
        val pinSet = pins.toSet()
        val recentsOut = recents.mapNotNull { index[it] }.map { item ->
            Ranked.Recent(item, stem(item) in pinSet)
        }
        val library = items.map { item ->
            Ranked.Recent(item, stem(item) in pinSet)
        }
        return Shelves(favourites, recentsOut, library)
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
