package com.portavion.stickerboard

import android.content.Context
import android.graphics.BitmapFactory
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library screen's state. [MainActivity] owns it so images shared in from other apps and
 * actions on the screen report through the same [snackbar].
 *
 * Selection mode is [selected] being non-empty, and it only ever holds stickers on the [shelf]
 * on screen. Stickers in [pendingDelete] are hidden on every shelf but stay on disk until their
 * Undo snackbar closes without Undo.
 */
class LibraryState(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val ranks = RankStore.of(app)

    /** Where the stickers live on disk. */
    val store = StickerStore(app)

    /** The one snackbar host for every result on this screen. */
    val snackbar = SnackbarHostState()

    /** Every sticker on disk, sorted onto the Favourites, Recents and Library shelves. */
    var shelves by mutableStateOf(Shelves.empty<Sticker>())
        private set

    /** The shelf on screen. Change it with [show]. */
    var shelf by mutableStateOf(Shelf.Library)
        private set

    /** Whether stickerboard is turned on in the system keyboard settings. */
    var keyboardOn by mutableStateOf(false)
        private set

    /** Stems of the selected stickers. Empty outside selection mode. */
    var selected by mutableStateOf(emptySet<String>())

    /** Stickers deleted on screen whose files wait for the Undo snackbar to close. */
    var pendingDelete by mutableStateOf(emptyList<Sticker>())
        private set

    /** Stem of the sticker added last, marked NEW until the next touch. */
    var newest by mutableStateOf<String?>(null)

    /** The open editor, or null while the grid shows. */
    var session by mutableStateOf<EditorSession?>(null)

    /** Whether the user tapped Done on the keyboard setup card. */
    var setupDone by mutableStateOf(prefs.getBoolean("setup_done", false))
        private set

    /** Whether the user has dismissed the tap-to-edit hint or opened the editor. */
    var hintSeen by mutableStateOf(prefs.getBoolean("hint_seen", false))
        private set

    init {
        refresh()
        // Open on what the user sends most; a library nothing was sent from yet opens on everything.
        if (shelves.recents.isNotEmpty()) shelf = Shelf.Recents
    }

    /** Every sticker in the library except those waiting on Undo, newest first. */
    val library: List<Sticker>
        get() {
            val gone = pendingDelete.mapTo(HashSet()) { it.stem }
            return shelves.library.map { it.item }.filterNot { it.stem in gone }
        }

    /** Stickers on the current shelf, in shelf order, except those waiting on Undo. */
    val visible: List<Ranked<Sticker>>
        get() {
            val gone = pendingDelete.mapTo(HashSet()) { it.stem }
            return shelves[shelf].filterNot { it.item.stem in gone }
        }

    /** Whether every selected sticker is already a favourite, so the star removes them. */
    val selectionPinned: Boolean
        get() = visible.filter { it.item.stem in selected }.all { it.pinned }

    /** The one selected sticker when it can be moved along the Favourites shelf, else null. */
    val movable: Ranked.Favourite<Sticker>?
        get() {
            if (shelf != Shelf.Favourites) return null
            val stem = selected.singleOrNull() ?: return null
            return shelves.favourites.find { it.item.stem == stem }
        }

    /** Re-reads the stickers on disk, their pins and recents, and whether the keyboard is turned on. */
    fun refresh() {
        shelves = ranks.shelves(store)
        // A sticker that left the shelf on screen can't stay selected where nobody sees it.
        val here = visible.mapTo(HashSet()) { it.item.stem }
        if (!here.containsAll(selected)) selected = selected.filterTo(LinkedHashSet()) { it in here }
        keyboardOn = app.getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.any { it.packageName == app.packageName }
    }

    /** Puts [next] on screen. Changing shelf ends selection. */
    fun show(next: Shelf) {
        if (next == shelf) return
        shelf = next
        selected = emptySet()
    }

    /**
     * Stars the selected stickers, in the order they were selected, or unstars them all when
     * every one is already a favourite. Unstarred stickers leave the Favourites shelf, and with
     * them the selection.
     */
    fun togglePins() {
        val byStem = visible.associateBy { it.item.stem }
        val chosen = selected.mapNotNull { byStem[it] }
        if (chosen.all { it.pinned }) chosen.forEach { ranks.unpin(it.item) }
        else chosen.filterNot { it.pinned }.forEach { ranks.pin(it.item) }
        refresh()
    }

    /** Moves the [movable] favourite one place along the shelf. It stays selected for the next tap. */
    fun moveSelected(move: Move) {
        val favourite = movable ?: return
        ranks.movePin(favourite.item, move)
        refresh()
    }

    /** Hides the setup card for good once the keyboard is on. */
    fun finishSetup() {
        setupDone = true
        prefs.edit().putBoolean("setup_done", true).apply()
    }

    /** Hides the tap-to-edit hint for good. */
    fun dismissHint() {
        hintSeen = true
        prefs.edit().putBoolean("hint_seen", true).apply()
    }

    /** Opens the editor on [sticker] unless one is already open. */
    fun open(scope: CoroutineScope, sticker: Sticker) {
        if (session != null) return
        if (!hintSeen) dismissHint()
        session = EditorSession.start(app, scope, sticker)
    }

    /**
     * Shows [message], replacing any snackbar on screen. A replaced snackbar resolves as
     * dismissed, so a pending delete behind it goes through.
     */
    suspend fun say(message: String, action: String? = null): SnackbarResult {
        snackbar.currentSnackbarData?.dismiss()
        // A snackbar with an action defaults to Indefinite, which would hold a delete forever.
        return snackbar.showSnackbar(
            message,
            actionLabel = action,
            duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long,
        )
    }

    /**
     * Reports stickers just added from Photos, Paste or a share, and marks the newest one.
     * An empty [added] means every image failed. One editable sticker gets a Make sticker action.
     */
    fun reportAdded(scope: CoroutineScope, added: List<Sticker>) {
        if (added.isEmpty()) {
            scope.launch { say(app.getString(R.string.lib_add_failed)) }
            return
        }
        refresh()
        // Never-sent stickers aren't on Recents or Favourites, so the NEW one only shows on Library.
        show(Shelf.Library)
        // The store writes in order, so the last one added sorts first in the grid.
        newest = added.last().stem
        val one = added.singleOrNull()?.takeIf { it.editable }
        scope.launch {
            val result = say(
                app.resources.getQuantityString(R.plurals.lib_added, added.size, added.size),
                one?.let { app.getString(R.string.lib_make_sticker) },
            )
            if (result == SnackbarResult.ActionPerformed && one != null) open(scope, one)
        }
    }

    /**
     * Hides the selected stickers and offers Undo. The files go only when the snackbar closes
     * without Undo; if [scope] dies first, the stickers stay.
     */
    fun deleteSelected(scope: CoroutineScope) {
        val doomed = library.filter { it.stem in selected }
        selected = emptySet()
        if (doomed.isEmpty()) return
        val stems = doomed.mapTo(HashSet()) { it.stem }
        pendingDelete = pendingDelete + doomed
        scope.launch {
            try {
                val result = say(
                    app.resources.getQuantityString(R.plurals.lib_deleted, doomed.size, doomed.size),
                    app.getString(R.string.lib_undo),
                )
                if (result != SnackbarResult.ActionPerformed) doomed.forEach(store::delete)
            } finally {
                pendingDelete = pendingDelete.filterNot { it.stem in stems }
                refresh()
            }
        }
    }
}

/** One row of the empty library: a way to get stickers in. Rows without [onClick] only explain. */
private data class Way(val icon: Painter, val title: Int, val sub: Int, val onClick: (() -> Unit)?)

/** The sticker library: setup card, sticker grid, selection, and add, paste, backup and restore. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(state: LibraryState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var showPolicy by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var zipJob by remember { mutableStateOf<Job?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        state.reportAdded(scope, uris.mapNotNull { (state.store.import(context, it) as? ImportResult.Saved)?.sticker })
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        zipJob = scope.launch {
            try {
                val written = withContext(Dispatchers.IO) { state.store.export(context, uri) }
                ensureActive()
                state.say(
                    if (written == null) context.getString(R.string.export_failed)
                    else context.resources.getQuantityString(R.plurals.exported, written, written)
                )
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
                val result = withContext(Dispatchers.IO) { state.store.restore(context, uri) }
                ensureActive()
                when (result) {
                    is RestoreResult.Done -> {
                        state.refresh()
                        state.say(context.resources.getQuantityString(R.plurals.imported, result.written, result.written))
                    }
                    RestoreResult.NotAZip -> state.say(context.getString(R.string.import_not_zip))
                    RestoreResult.Unreadable -> state.say(context.getString(R.string.import_unreadable))
                }
            } finally {
                zipJob = null
            }
        }
    }

    val pickPhotos = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val paste = {
        when (val result = pasteStickers(context, state.store)) {
            is PasteResult.Saved -> state.reportAdded(scope, result.stickers)
            PasteResult.Empty -> scope.launch { state.say(context.getString(R.string.paste_empty)) }
            PasteResult.NotAnImage -> scope.launch { state.say(context.getString(R.string.paste_not_image)) }
            PasteResult.Unreadable -> scope.launch { state.say(context.getString(R.string.paste_unreadable)) }
        }
        Unit
    }

    val open = state.session
    if (open != null) {
        EditorRoute(open, state.store, onDone = {
            state.session = null
            state.refresh()
        })
        return
    }

    if (showPolicy) {
        BackHandler { showPolicy = false }
        PrivacyScreen(onClose = { showPolicy = false })
        return
    }

    val total = state.library.size
    val visible = state.visible
    val selecting = state.selected.isNotEmpty()
    val showFabs = !selecting && total > 0
    BackHandler(enabled = selecting) { state.selected = emptySet() }

    Scaffold(
        // Any touch ends the NEW marker. Observed on the way down, so it never steals the touch.
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                state.newest = null
            }
        },
        topBar = {
            if (selecting) {
                val count = state.selected.size
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.lib_selected, count, count)) },
                    navigationIcon = {
                        IconButton(onClick = { state.selected = emptySet() }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.lib_clear_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = state::togglePins) {
                            if (state.selectionPinned) {
                                Icon(Icons.Rounded.Star, stringResource(R.string.lib_unpin))
                            } else {
                                Icon(painterResource(R.drawable.ic_lib_star_outline), stringResource(R.string.lib_pin))
                            }
                        }
                        state.movable?.let { favourite ->
                            IconButton(
                                onClick = { state.moveSelected(Move.Earlier) },
                                enabled = favourite.canMoveEarlier,
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.lib_move_earlier))
                            }
                            IconButton(
                                onClick = { state.moveSelected(Move.Later) },
                                enabled = favourite.canMoveLater,
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.lib_move_later))
                            }
                        }
                        IconButton(onClick = { state.deleteSelected(scope) }) {
                            Icon(Icons.Rounded.Delete, stringResource(R.string.lib_delete))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.secondaryContainer),
                )
            } else {
                TopAppBar(
                    title = { Wordmark(fontSize = 26.sp) },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, stringResource(R.string.lib_more))
                            }
                            val zipBusy = zipJob?.isActive == true
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.lib_backup)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_lib_archive), null) },
                                    enabled = !zipBusy,
                                    onClick = {
                                        menuOpen = false
                                        // Settle an Undo still on screen first, so deleted stickers stay out of the backup.
                                        state.snackbar.currentSnackbarData?.dismiss()
                                        exporter.launch("stickerboard-${LocalDate.now()}.zip")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.lib_restore)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_lib_unarchive), null) },
                                    enabled = !zipBusy,
                                    onClick = {
                                        menuOpen = false
                                        importer.launch(arrayOf("application/zip", "application/octet-stream"))
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.privacy_policy)) },
                                    leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                                    onClick = {
                                        menuOpen = false
                                        showPolicy = true
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
                )
            }
        },
        floatingActionButton = {
            if (showFabs) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FloatingActionButton(
                        onClick = paste,
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        containerColor = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer,
                        elevation = FloatingActionButtonDefaults.elevation(2.dp, 2.dp, 2.dp, 2.dp),
                    ) {
                        Row(
                            Modifier.padding(start = 14.dp, end = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_lib_paste), null, Modifier.size(20.dp))
                            Text(stringResource(R.string.lib_paste), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    ExtendedFloatingActionButton(
                        onClick = pickPhotos,
                        icon = { Icon(painterResource(R.drawable.ic_lib_add_photo), null) },
                        text = { Text(stringResource(R.string.lib_add)) },
                        shape = RoundedCornerShape(18.dp),
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(state.snackbar) },
    ) { inner ->
        val dir = LocalLayoutDirection.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(84.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = inner.calculateStartPadding(dir) + 16.dp,
                end = inner.calculateEndPadding(dir) + 16.dp,
                top = inner.calculateTopPadding() + 4.dp,
                // Room for the two FABs so the last row can scroll clear of them.
                bottom = inner.calculateBottomPadding() + if (showFabs) 136.dp else 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Keyboard setup card: stays until the keyboard is on and the user taps Done.
            if (!(state.keyboardOn && state.setupDone)) {
                item(key = "header/setup", span = { GridItemSpan(maxLineSpan) }) {
                    val test = rememberTextFieldState()
                    // The sticker the keyboard sent into the test field, proof that setup worked.
                    var received by remember { mutableStateOf<ImageBitmap?>(null) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(colors.primaryContainer)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.lib_setup_title),
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                            color = colors.onPrimaryContainer,
                        )
                        // Step 1: turn the keyboard on in system settings.
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (state.keyboardOn) colors.primary else colors.surfaceContainerLowest),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.keyboardOn) {
                                    Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = colors.onPrimary)
                                } else {
                                    Text("1", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.lib_setup_step1), style = MaterialTheme.typography.bodyMedium)
                                if (state.keyboardOn) {
                                    Text(
                                        stringResource(R.string.lib_setup_step1_done),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                } else {
                                    Button(onClick = {
                                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                    }) { Text(stringResource(R.string.lib_setup_open_settings)) }
                                }
                            }
                        }
                        // Step 2: try it in a real field, so switching keyboards has somewhere to type.
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(colors.surfaceContainerLowest),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("2", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.lib_setup_step2), style = MaterialTheme.typography.bodyMedium)
                                // Accepts images, so a sticker sent from the keyboard lands here instead of the clipboard.
                                BasicTextField(
                                    state = test,
                                    enabled = state.keyboardOn,
                                    lineLimits = TextFieldLineLimits.SingleLine,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                                    cursorBrush = SolidColor(colors.primary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .contentReceiver { content ->
                                            if (!content.hasMediaType(MediaType.Image)) return@contentReceiver content
                                            content.consume { item ->
                                                val bitmap = item.uri?.let { uri ->
                                                    context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                                                }
                                                if (bitmap != null) received = bitmap.asImageBitmap()
                                                bitmap != null
                                            }
                                        },
                                    decorator = { field ->
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    if (state.keyboardOn) colors.surfaceContainerLowest
                                                    else colors.surfaceContainerLowest.copy(alpha = 0.6f)
                                                )
                                                .padding(horizontal = 16.dp, vertical = 16.dp),
                                        ) {
                                            if (test.text.isEmpty()) {
                                                Text(
                                                    stringResource(R.string.lib_setup_field),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = colors.onSurfaceVariant,
                                                )
                                            }
                                            field()
                                        }
                                    },
                                )
                                received?.let { sticker ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Image(sticker, contentDescription = null, modifier = Modifier.size(48.dp))
                                        Text(stringResource(R.string.lib_setup_received), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                        if (state.keyboardOn) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = state::finishSetup) {
                                    Text(stringResource(R.string.lib_setup_done))
                                }
                                FilledTonalButton(
                                    onClick = {
                                        context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = colors.surfaceContainerLowest,
                                    ),
                                ) {
                                    Icon(painterResource(R.drawable.ic_lib_keyboard), null, Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.lib_setup_switch))
                                }
                            }
                        }
                    }
                }
            }

            if (total == 0) {
                // Empty library: a small die-cut cluster, then the three ways stickers get in.
                item(key = "header/empty", span = { GridItemSpan(maxLineSpan) }) {
                    val ways = listOf(
                        Way(painterResource(R.drawable.ic_lib_photo_library), R.string.lib_way_photos, R.string.lib_way_photos_sub, pickPhotos),
                        Way(painterResource(R.drawable.ic_lib_paste), R.string.lib_way_paste, R.string.lib_way_paste_sub, paste),
                        Way(rememberVectorPainter(Icons.Rounded.Share), R.string.lib_way_share, R.string.lib_way_share_sub, null),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(top = 20.dp, bottom = 4.dp).size(250.dp, 170.dp)) {
                            listOf(
                                Triple(Icons.Rounded.Favorite, colors.primary, Triple(6, 30, -12f)),
                                Triple(Icons.Rounded.Star, colors.tertiary, Triple(76, 0, 5f)),
                                Triple(Icons.Rounded.ThumbUp, colors.secondaryContainer, Triple(146, 44, -4f)),
                            ).forEach { (icon, fill, place) ->
                                val (x, y, angle) = place
                                val shape = RoundedCornerShape(26.dp)
                                Box(
                                    Modifier
                                        .offset(x.dp, y.dp)
                                        .rotate(angle)
                                        .size(98.dp)
                                        .shadow(4.dp, shape)
                                        .background(colors.surfaceContainerLowest, shape)
                                        .padding(6.dp)
                                        .background(fill, shape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        icon,
                                        null,
                                        Modifier.size(44.dp),
                                        tint = if (fill == colors.primary) colors.onPrimary else colors.onSurface,
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.lib_empty_title),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            stringResource(R.string.lib_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp, bottom = 22.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ways.forEach { way ->
                                val shape = RoundedCornerShape(18.dp)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(shape)
                                        .background(colors.surfaceContainerLowest)
                                        .border(1.dp, colors.outlineVariant, shape)
                                        .then(way.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                                        .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(colors.primaryContainer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(way.icon, null, tint = colors.primary)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            stringResource(way.title),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            stringResource(way.sub),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                    if (way.onClick != null) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                            null,
                                            tint = colors.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item(key = "header/shelves", span = { GridItemSpan(maxLineSpan) }) {
                    PrimaryTabRow(
                        selectedTabIndex = state.shelf.ordinal,
                        containerColor = colors.background,
                        contentColor = colors.primary,
                        divider = { HorizontalDivider(color = colors.outlineVariant) },
                    ) {
                        Shelf.entries.forEach { shelf ->
                            val (icon, label) = when (shelf) {
                                Shelf.Favourites -> R.drawable.ic_shelf_favourites to R.string.shelf_favourites
                                Shelf.Recents -> R.drawable.ic_shelf_recents to R.string.shelf_recents
                                Shelf.Library -> R.drawable.ic_shelf_library to R.string.shelf_library
                            }
                            Tab(
                                selected = shelf == state.shelf,
                                onClick = { state.show(shelf) },
                                icon = { Icon(painterResource(icon), null) },
                                text = { Text(stringResource(label)) },
                                selectedContentColor = colors.primary,
                                unselectedContentColor = colors.onSurfaceVariant,
                            )
                        }
                    }
                }

                // The total only reads right under Library; on the other shelves it looks like their size.
                if (state.shelf == Shelf.Library) {
                    item(key = "header/count", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            pluralStringResource(R.plurals.lib_count, total, total),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp, top = 6.dp),
                        )
                    }
                }

                if (visible.isEmpty()) {
                    // Library is never empty here: an empty library shows the welcome above instead.
                    item(key = "header/shelf-empty", span = { GridItemSpan(maxLineSpan) }) {
                        val (icon, title, sub) = if (state.shelf == Shelf.Favourites) {
                            Triple(R.drawable.ic_shelf_favourites, R.string.lib_no_favourites, R.string.lib_no_favourites_sub)
                        } else {
                            Triple(R.drawable.ic_shelf_recents, R.string.lib_no_recents, R.string.lib_no_recents_sub)
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier
                                    .padding(bottom = 6.dp)
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(colors.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(painterResource(icon), null, tint = colors.primary)
                            }
                            Text(
                                stringResource(title),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                stringResource(sub),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                // One-time hint: tapping a sticker opens the editor.
                if (!state.hintSeen && !selecting && visible.isNotEmpty()) {
                    item(key = "header/hint", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.inverseSurface)
                                .padding(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.lib_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.inverseOnSurface,
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                            )
                            TextButton(onClick = state::dismissHint) {
                                Text(stringResource(R.string.lib_got_it), color = colors.inversePrimary)
                            }
                        }
                    }
                }

                items(visible, key = { "sticker/${it.item.stem}" }) { ranked ->
                    val sticker = ranked.item
                    val isSelected = sticker.stem in state.selected
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val press by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")
                    val shape = RoundedCornerShape(18.dp)
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .graphicsLayer {
                                scaleX = press
                                scaleY = press
                            }
                            .clip(shape)
                            .background(colors.surfaceContainer)
                            .then(if (isSelected) Modifier.border(3.dp, colors.primary, shape) else Modifier)
                            .combinedClickable(
                                interactionSource = interaction,
                                indication = ripple(),
                                onLongClick = { state.selected += sticker.stem },
                                onClick = {
                                    when {
                                        selecting -> state.selected =
                                            if (isSelected) state.selected - sticker.stem else state.selected + sticker.stem
                                        sticker.editable -> state.open(scope, sticker)
                                        // GIFs can't be cut out; the GIF badge says why nothing opens.
                                        else -> Unit
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val size by animateFloatAsState(if (isSelected) 0.66f else 0.76f, label = "select")
                        StickerThumb(sticker, Modifier.fillMaxSize(size))
                        if (selecting) {
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) colors.primary else colors.surfaceContainerLowest)
                                    .border(2.dp, if (isSelected) colors.primary else colors.onSurfaceVariant, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = colors.onPrimary)
                            }
                        }
                        if (!sticker.editable) {
                            Text(
                                stringResource(R.string.lib_badge_gif),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(colors.surfaceContainerLowest)
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                        // Everything on Favourites is pinned, so only the other shelves mark pins.
                        if (ranked.pinned && ranked !is Ranked.Favourite) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(colors.tertiary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Star, null, Modifier.size(13.dp), tint = colors.onTertiary)
                            }
                        }
                        if (sticker.stem == state.newest) {
                            Text(
                                stringResource(R.string.lib_badge_new),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.onTertiary,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(colors.tertiary)
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
