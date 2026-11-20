package com.portavion.stickerboard

import android.content.Context
import android.content.SharedPreferences

class RankStore private constructor(private val prefs: SharedPreferences) {
    fun shelves(store: StickerStore): Shelves<Sticker> {
        val stickers = store.list()
        val live = stickers.map { it.stem }.toSet()
        return update { it.retaining(live) }.shelves(stickers) { it.stem }
    }

    fun pin(sticker: Sticker) {
        update { it.withPin(sticker.stem) }
    }

    fun unpin(sticker: Sticker) {
        update { it.withoutPin(sticker.stem) }
    }

    fun movePin(sticker: Sticker, move: Move) {
        update { it.withPinMoved(sticker.stem, move) }
    }

    fun recordSend(sticker: Sticker) {
        update { it.withSend(sticker.stem) }
    }

    private fun update(step: (Ranking) -> Ranking): Ranking = synchronized(this) {
        val current = Ranking.of(
            splitStems(prefs.getString(KEY_PINS, null)),
            splitStems(prefs.getString(KEY_RECENTS, null)),
        )
        val next = step(current)
        if (next != current) {
            prefs.edit()
                .putString(KEY_PINS, joinStems(next.pins))
                .putString(KEY_RECENTS, joinStems(next.recents))
                .apply()
        }
        next
    }

    companion object {
        private const val PREFS = "ranks"
        private const val KEY_PINS = "pins"
        private const val KEY_RECENTS = "recents"

        private val lock = Any()
        private var instance: RankStore? = null

        fun of(context: Context): RankStore = synchronized(lock) {
            instance ?: RankStore(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
