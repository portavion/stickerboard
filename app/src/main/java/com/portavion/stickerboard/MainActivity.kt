package com.portavion.stickerboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    private val library by lazy { LibraryState(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) importShared(intent)
        enableEdgeToEdge()
        setContent {
            StickerboardTheme {
                LibraryScreen(library)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importShared(intent)
    }

    override fun onResume() {
        super.onResume()
        // Picks up stickers and keyboard settings changed while the app was away.
        library.refresh()
    }

    private fun importShared(intent: Intent?) {
        val uris = sharedImageUris(intent)
        if (uris.isNullOrEmpty()) return
        val added = uris.mapNotNull { (library.store.import(this, it) as? ImportResult.Saved)?.sticker }
        library.reportAdded(lifecycleScope, added)
    }
}

private fun sharedImageUris(intent: Intent?): List<Uri>? =
    when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        )
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
