package com.portavion.stickerboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) importShared(intent)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                LibraryScreen(resumeTick.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importShared(intent)
        resumeTick.intValue++
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    private fun importShared(intent: Intent?) {
        val uris = sharedImageUris(intent) ?: return
        val store = StickerStore(this)
        uris.forEach { store.import(this, it) }
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

@Composable
private fun LibraryScreen(resumeTick: Int) {
    val context = LocalContext.current
    val store = remember { StickerStore(context) }
    val ranks = remember { RankStore.of(context) }
    var tick by remember { mutableIntStateOf(0) }
    val shelves = remember(tick, resumeTick) { ranks.shelves(store.list()) }
    var shelf by remember { mutableStateOf(Shelf.Recents) }
    val enabled = remember(resumeTick) { imeEnabled(context) }
    var editor by remember { mutableStateOf<Editor?>(null) }
    var showPolicy by remember { mutableStateOf(false) }
    var editJob by remember { mutableStateOf<Job?>(null) }
    var zipJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        uris.forEach { store.import(context, it) }
        tick++
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        zipJob = scope.launch {
            try {
                val written = withContext(Dispatchers.IO) { store.export(context, uri) }
                ensureActive()
                if (written == null) toast(context, R.string.export_failed)
                else toast(context, context.resources.getQuantityString(R.plurals.exported, written, written))
            } finally {
                zipJob = null
            }
        }
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        zipJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { store.restore(context, uri) }
                ensureActive()
                when (result) {
                    is RestoreResult.Done -> {
                        tick++
                        toast(context, context.resources.getQuantityString(R.plurals.imported, result.written, result.written))
                    }
                    RestoreResult.NotAZip -> toast(context, R.string.import_not_zip)
                    RestoreResult.Unreadable -> toast(context, R.string.import_unreadable)
                }
            } finally {
                zipJob = null
            }
        }
    }

    val currentEditor = editor
    if (currentEditor != null) {
        BackHandler {
            editJob?.cancel()
            editor = null
        }
        EditorScreen(
            state = currentEditor,
            onPick = { look -> editor = (editor as? Editor.Choosing)?.copy(chosen = look) },
            onSave = {
                val s = editor as Editor.Choosing
                if (store.saveLook(s.sticker, s.previews.getValue(s.chosen)) == null) {
                    toast(context, R.string.save_failed)
                }
                editor = null
                tick++
            },
            onRevert = {
                editor?.let { store.clearLook(it.sticker) }
                editor = null
                tick++
            },
            onClose = {
                editJob?.cancel()
                editor = null
            },
        )
        return
    }

    if (showPolicy) {
        BackHandler { showPolicy = false }
        PrivacyScreen(onClose = { showPolicy = false })
        return
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Text("Add stickers")
            }
            FilledTonalButton(onClick = {
                when (val result = pasteStickers(context, store)) {
                    is PasteResult.Saved -> tick++
                    PasteResult.Empty -> toast(context, R.string.paste_empty)
                    PasteResult.NotAnImage -> toast(context, R.string.paste_not_image)
                    PasteResult.Unreadable -> toast(context, R.string.paste_unreadable)
                }
            }) { Text("Paste") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { exporter.launch("stickerboard-${today()}.zip") },
                enabled = zipJob?.isActive != true
            ) { Text("Export") }
            FilledTonalButton(
                onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) },
                enabled = zipJob?.isActive != true
            ) { Text("Import") }
        }
        if (shelves.libraryEmpty) {
            Text("No stickers yet. Add PNG, WebP, GIF, or JPEG files, or paste an image.")
            Spacer(Modifier.weight(1f))
        } else {
            val tabShelves = listOf(Shelf.Favourites, Shelf.Recents)
            TabRow(selectedTabIndex = tabShelves.indexOf(shelf)) {
                tabShelves.forEach { s ->
                    Tab(
                        selected = s == shelf,
                        onClick = { shelf = s },
                        text = { Text(stringResource(shelfLabel(s))) },
                    )
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val cols = maxOf(1, (maxWidth / 88.dp).toInt())
                val rows = maxOf(1, (maxHeight / 96.dp).toInt())
                val pageSize = cols * rows
                val cells = shelves[shelf]
                val pageCount = maxOf(1, (cells.size + pageSize - 1) / pageSize)
                key(shelf) {
                    val pagerState = rememberPagerState(pageCount = { pageCount })
                    Column(Modifier.fillMaxSize()) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) { page ->
                            val slice = cells.drop(page * pageSize).take(pageSize)
                            Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                repeat(rows) { r ->
                                    Row(
                                        Modifier.weight(1f).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        repeat(cols) { c ->
                                            val ranked = slice.getOrNull(r * cols + c)
                                            if (ranked == null) {
                                                Spacer(Modifier.weight(1f).fillMaxHeight())
                                            } else {
                                                val sticker = ranked.item
                                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                                    StickerThumb(
                                                        sticker,
                                                        Modifier.fillMaxSize().then(
                                                            if (sticker.editable) {
                                                                Modifier.clickable {
                                                                    if (editJob?.isActive == true) return@clickable
                                                                    editor = Editor.Segmenting(sticker)
                                                                    editJob = scope.launch {
                                                                        val next = startEditing(context, sticker)
                                                                        ensureActive()
                                                                        editor = next
                                                                    }
                                                                }
                                                            } else Modifier
                                                        )
                                                    )
                                                    ThumbMark(
                                                        if (ranked.pinned) "★" else "☆",
                                                        Alignment.TopStart,
                                                    ) {
                                                        if (ranked.pinned) ranks.unpin(sticker) else ranks.pin(sticker)
                                                        tick++
                                                    }
                                                    ThumbMark("×", Alignment.TopEnd) {
                                                        store.delete(sticker)
                                                        tick++
                                                    }
                                                    if (ranked is Ranked.Favourite<Sticker>) {
                                                        ThumbMark(
                                                            "‹",
                                                            Alignment.BottomStart,
                                                            enabled = ranked.canMoveEarlier,
                                                        ) {
                                                            ranks.movePin(sticker, Move.Earlier)
                                                            tick++
                                                        }
                                                        ThumbMark(
                                                            "›",
                                                            Alignment.BottomEnd,
                                                            enabled = ranked.canMoveLater,
                                                        ) {
                                                            ranks.movePin(sticker, Move.Later)
                                                            tick++
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (pageCount > 1) {
                            Text(
                                "${pagerState.currentPage + 1} / $pageCount",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
        TextButton(onClick = { showPolicy = true }) {
            Text(
                stringResource(R.string.privacy_policy),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun BoxScope.ThumbMark(
    label: String,
    alignment: Alignment,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Text(
            label,
            modifier = Modifier
                .align(alignment)
                .size(32.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        )
    }
}

private fun shelfLabel(shelf: Shelf) = when (shelf) {
    Shelf.Favourites -> R.string.shelf_favourites
    Shelf.Recents -> R.string.shelf_recents
}

private fun imeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(InputMethodManager::class.java)
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

private fun toast(context: Context, resId: Int) {
    Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
}

private fun toast(context: Context, text: CharSequence) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

private fun today() = LocalDate.now().toString()
