package com.portavion.stickerboard

import android.content.ClipboardManager
import android.content.Context

sealed interface PasteResult {
    data class Saved(val stickers: List<Sticker>) : PasteResult {
        init {
            require(stickers.isNotEmpty())
        }
    }
    data object Empty : PasteResult
    data object NotAnImage : PasteResult
    data object Unreadable : PasteResult
}

/** Saves every image on the clipboard into [store], in clipboard order. */
fun pasteStickers(context: Context, store: StickerStore): PasteResult {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?: return PasteResult.Empty
    if (clip.itemCount == 0) return PasteResult.Empty
    val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.distinct()
    if (uris.isEmpty()) return PasteResult.Empty
    val saved = mutableListOf<Sticker>()
    var unreadable = false
    for (uri in uris) {
        when (val r = store.import(context, uri)) {
            is ImportResult.Saved -> saved += r.sticker
            ImportResult.Unreadable -> unreadable = true
            ImportResult.NotAnImage -> Unit
        }
    }
    if (saved.isNotEmpty()) return PasteResult.Saved(saved)
    if (unreadable) return PasteResult.Unreadable
    return PasteResult.NotAnImage
}
