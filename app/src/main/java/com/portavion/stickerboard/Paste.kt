package com.portavion.stickerboard

import android.content.ClipboardManager
import android.content.Context

sealed interface PasteResult {
    data class Saved(val count: Int) : PasteResult {
        init {
            require(count >= 1)
        }
    }
    data object Empty : PasteResult
    data object NotAnImage : PasteResult
    data object Unreadable : PasteResult
}

fun pasteStickers(context: Context, store: StickerStore): PasteResult {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?: return PasteResult.Empty
    if (clip.itemCount == 0) return PasteResult.Empty
    val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.distinct()
    if (uris.isEmpty()) return PasteResult.Empty
    var saved = 0
    var unreadable = false
    for (uri in uris) {
        when (store.import(uri)) {
            is ImportResult.Saved -> saved++
            ImportResult.Unreadable -> unreadable = true
            ImportResult.NotAnImage -> Unit
        }
    }
    if (saved > 0) return PasteResult.Saved(saved)
    if (unreadable) return PasteResult.Unreadable
    return PasteResult.NotAnImage
}
