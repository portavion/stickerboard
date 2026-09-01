package com.portavion.stickerboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                LibraryScreen(resumeTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

@Composable
private fun LibraryScreen(resumeTick: Int) {
    val context = LocalContext.current
    val store = remember { StickerStore(context) }
    var tick by remember { mutableIntStateOf(0) }
    val stickers = remember(tick, resumeTick) { store.list() }
    val enabled = remember(resumeTick) { imeEnabled(context) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        uris.forEach { store.import(it) }
        tick++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("stickerboard", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (enabled) {
                "Keyboard on. In a chat, long-press Gboard’s spacebar and pick stickerboard."
            } else {
                "Enable the stickerboard keyboard in system settings, then pick it from Gboard."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }) {
            Text(if (enabled) "Keyboard settings" else "Enable keyboard")
        }
        if (enabled) {
            FilledTonalButton(onClick = {
                context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }) {
                Text("Switch keyboard")
            }
        }
        Button(onClick = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            Text("Add stickers")
        }
        if (stickers.isEmpty()) {
            Text("No stickers yet. Add PNG, WebP, GIF, or JPEG files.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(88.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(stickers, key = { it.file.name }) { sticker ->
                    Box {
                        StickerThumb(
                            sticker,
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        )
                        IconButton(
                            onClick = {
                                store.delete(sticker)
                                tick++
                            },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Text("×")
                        }
                    }
                }
            }
        }
    }
}

private fun imeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(InputMethodManager::class.java)
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}
