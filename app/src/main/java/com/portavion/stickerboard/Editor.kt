package com.portavion.stickerboard

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Segmentation {
    data class Cut(val raster: Raster) : Segmentation
    sealed interface Failure : Segmentation {
        data object Unreadable : Failure
        data object NoSubject : Failure
        data object ModelUnavailable : Failure
        data object Crashed : Failure
    }
}

/** What the keyboard can send for a sticker. */
sealed interface Style {
    /** The original image with no look. Saving it clears the saved look. */
    data object Original : Style

    /** A [Look] rendered from this session's cutout. */
    data class Styled(val look: Look) : Style

    /**
     * The look saved before this session. A saved PNG can't be traced back to the [Look] that
     * made it, so it stands as its own choice instead of being guessed onto Cutout or White border.
     */
    data object Saved : Style
}

/** Why segmentation is still running: looking for the subject, or waiting for Play services to download the model. */
enum class SegmentPhase { Finding, Downloading }

/** The editor screen's whole state. The UI renders it and nothing else. */
sealed interface Editor {
    val sticker: Sticker

    /** [source] is the decoded original, null only while it is still decoding. */
    data class Segmenting(
        override val sticker: Sticker,
        val source: Bitmap?,
        val phase: SegmentPhase,
    ) : Editor

    /** [previews] holds every style on offer, in display order. */
    data class Choosing(
        override val sticker: Sticker,
        val previews: Map<Style, Bitmap>,
        val chosen: Style,
    ) : Editor {
        /** The style the keyboard sends today. */
        val sending: Style get() = if (sticker.look == null) Style.Original else Style.Saved
    }

    /** [source] is the decoded original, null only when [why] is [Segmentation.Failure.Unreadable]. */
    data class Failed(
        override val sticker: Sticker,
        val source: Bitmap?,
        val why: Segmentation.Failure,
    ) : Editor
}

/**
 * Decodes [sticker]'s original (or reuses [decoded]), cuts out its subject and renders every style.
 * Each [Editor.Segmenting] step goes to [onProgress] before the final state is returned.
 */
suspend fun startEditing(
    context: Context,
    sticker: Sticker,
    decoded: Bitmap?,
    onProgress: (Editor.Segmenting) -> Unit,
): Editor = withContext(Dispatchers.IO) {
    val source = decoded ?: StickerStore.decode(sticker.original, maxPx = 1024)
        ?: return@withContext Editor.Failed(sticker, null, Segmentation.Failure.Unreadable)
    onProgress(Editor.Segmenting(sticker, source, SegmentPhase.Finding))
    val saved = sticker.look?.let { StickerStore.decode(it, maxPx = 1024) }
    val seg = segment(context, source.atLeast(MIN_SEGMENT_SIDE)) {
        onProgress(Editor.Segmenting(sticker, source, SegmentPhase.Downloading))
    }
    when (seg) {
        is Segmentation.Cut -> Editor.Choosing(
            sticker = sticker,
            previews = buildMap {
                put(Style.Original, source)
                Look.all.forEach { put(Style.Styled(it), it.render(seg.raster).toBitmap()) }
                if (saved != null) put(Style.Saved, saved)
            },
            // Open on what the keyboard sends now when there is a saved look, else suggest White border.
            chosen = if (saved != null) Style.Saved else Style.Styled(Look.DieCut),
        )
        is Segmentation.Failure -> Editor.Failed(sticker, source, seg)
    }
}

/** Cuts the subject out of [source]. Calls [onDownloading] when it has to wait for Play services to fetch the model. */
suspend fun segment(context: Context, source: Bitmap, onDownloading: () -> Unit = {}): Segmentation = withContext(Dispatchers.IO) {
    val options = SubjectSegmenterOptions.Builder()
        .enableForegroundBitmap()
        .build()
    val segmenter = SubjectSegmentation.getClient(options)
    try {
        val installer = ModuleInstall.getClient(context)
        val available = try {
            awaitIo(installer.areModulesAvailable(segmenter)).areModulesAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "module check", e)
            return@withContext Segmentation.Failure.ModelUnavailable
        }
        if (!available) {
            onDownloading()
            val request = ModuleInstallRequest.newBuilder().addApi(segmenter).build()
            try {
                awaitIo(installer.installModules(request))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "module install", e)
                return@withContext Segmentation.Failure.ModelUnavailable
            }
        }
        val image = InputImage.fromBitmap(source, 0)
        var lastWait: Exception? = null
        repeat(40) {
            try {
                val result = awaitIo(segmenter.process(image))
                val fg = result.foregroundBitmap ?: return@withContext Segmentation.Failure.NoSubject
                val raster = fg.toRaster()
                if (raster.argb.none { it ushr 24 != 0 }) {
                    return@withContext Segmentation.Failure.NoSubject
                }
                return@withContext Segmentation.Cut(raster)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!moduleStillDownloading(e)) {
                    Log.w(TAG, "segment", e)
                    return@withContext Segmentation.Failure.Crashed
                }
                if (lastWait == null) onDownloading()
                lastWait = e
                delay(1500)
            }
        }
        Log.w(TAG, "segment timeout", lastWait)
        Segmentation.Failure.ModelUnavailable
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "segment", e)
        Segmentation.Failure.Crashed
    } finally {
        segmenter.close()
    }
}

/** One open editor: the sticker being edited, its [Editor] state, and the segmentation job that feeds it. */
class EditorSession private constructor(
    val sticker: Sticker,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var state: Editor by mutableStateOf(Editor.Segmenting(sticker, source = null, phase = SegmentPhase.Finding))
    private var job: Job? = null

    /** Stops segmentation if it is still running. */
    fun cancel() {
        job?.cancel()
    }

    /** Runs segmentation again after a failure, reusing the photo already decoded. */
    fun retry() {
        val failed = state as? Editor.Failed ?: return
        run(failed.source)
    }

    private fun run(decoded: Bitmap?) {
        job?.cancel()
        state = Editor.Segmenting(sticker, decoded, SegmentPhase.Finding)
        job = scope.launch {
            // Progress arrives on the IO thread. Snapshot state accepts writes from any thread.
            val next = startEditing(context, sticker, decoded) { state = it }
            ensureActive()
            state = next
        }
    }

    companion object {
        /** Opens an editor on [sticker] and starts segmenting it in [scope]. */
        fun start(context: Context, scope: CoroutineScope, sticker: Sticker): EditorSession {
            val session = EditorSession(sticker, context.applicationContext, scope)
            session.run(decoded = null)
            return session
        }
    }
}

/** The full-screen editor for [session]. Saves or clears the look through [store], then calls [onDone]. */
@Composable
fun EditorRoute(session: EditorSession, store: StickerStore, onDone: () -> Unit) {
    val context = LocalContext.current
    val close = {
        session.cancel()
        onDone()
    }
    BackHandler(onBack = close)
    EditorScreen(
        state = session.state,
        onPick = { style -> (session.state as? Editor.Choosing)?.let { session.state = it.copy(chosen = style) } },
        onSave = {
            val s = session.state as Editor.Choosing
            when (val style = s.chosen) {
                Style.Original -> {
                    store.clearLook(s.sticker)
                    onDone()
                }
                Style.Saved -> onDone()
                // On failure stay open so the person can try again or pick another style.
                is Style.Styled -> if (store.saveLook(s.sticker, s.previews.getValue(style)) == null) {
                    Toast.makeText(context, R.string.save_failed, Toast.LENGTH_LONG).show()
                } else {
                    onDone()
                }
            }
        },
        onRetry = session::retry,
        onUseOriginal = {
            store.clearLook(session.sticker)
            close()
        },
        onClose = close,
    )
}

@Composable
private fun EditorScreen(
    state: Editor,
    onPick: (Style) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onUseOriginal: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.look_close))
            }
            Text(
                stringResource(R.string.editor_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (state !is Editor.Failed) {
                Button(
                    onClick = onSave,
                    enabled = state is Editor.Choosing && state.chosen != state.sending,
                ) { Text(stringResource(R.string.look_save)) }
            }
        }

        // The stage: the photo while cutting out or after a failure, the chosen style on a checkerboard after.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .drawBehind {
                    drawRect(colors.surfaceContainerLowest)
                    val tile = 11.dp.toPx()
                    var row = 0
                    while (row * tile < size.height) {
                        var col = row and 1
                        while (col * tile < size.width) {
                            drawRect(colors.surfaceContainer, Offset(col * tile, row * tile), Size(tile, tile))
                            col += 2
                        }
                        row++
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val photo = when (state) {
                is Editor.Segmenting -> state.source
                is Editor.Failed -> state.source
                is Editor.Choosing -> null
            }
            if (photo != null) {
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (state is Editor.Choosing) {
                Image(
                    bitmap = state.previews.getValue(state.chosen).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            if (state is Editor.Segmenting) {
                // Dim the photo and sweep a soft band of light over it while the cutout runs.
                val sweep by rememberInfiniteTransition(label = "scan").animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "scan",
                )
                Box(
                    Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.38f)).drawBehind {
                        val band = 90.dp.toPx()
                        val top = (size.height - band) * sweep
                        drawRect(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                                startY = top,
                                endY = top + band,
                            ),
                            topLeft = Offset(0f, top),
                            size = Size(size.width, band),
                        )
                    }
                )
                Surface(shape = CircleShape, color = colors.surfaceContainerLowest, shadowElevation = 6.dp) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.5.dp,
                            trackColor = colors.primaryContainer,
                        )
                        Text(
                            stringResource(
                                if (state.phase == SegmentPhase.Downloading) R.string.editor_downloading
                                else R.string.editor_finding
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        when (state) {
            is Editor.Segmenting -> {
                if (state.phase == SegmentPhase.Downloading) {
                    Text(
                        stringResource(R.string.editor_downloading_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    )
                }
                // Empty tiles hold the place of the styles to come.
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp).alpha(0.6f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf(R.string.style_original, R.string.look_cutout, R.string.look_white_border).forEach { label ->
                        StyleTile(label, image = null, crop = false, selected = false, sending = false, onClick = null)
                    }
                }
            }
            is Editor.Choosing -> Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.previews.forEach { (style, bitmap) ->
                    StyleTile(
                        label = when (style) {
                            Style.Original -> R.string.style_original
                            Style.Saved -> R.string.style_saved
                            is Style.Styled -> when (style.look) {
                                Look.Cutout -> R.string.look_cutout
                                Look.DieCut -> R.string.look_white_border
                            }
                        },
                        image = bitmap,
                        crop = style == Style.Original,
                        selected = style == state.chosen,
                        sending = style == state.sending,
                        onClick = { onPick(style) },
                    )
                }
            }
            is Editor.Failed -> {
                val (title, body) = when (state.why) {
                    Segmentation.Failure.Unreadable -> R.string.look_unreadable to R.string.look_unreadable_body
                    Segmentation.Failure.NoSubject -> R.string.look_no_subject to R.string.look_no_subject_body
                    Segmentation.Failure.ModelUnavailable -> R.string.look_model_unavailable to R.string.look_model_unavailable_body
                    Segmentation.Failure.Crashed -> R.string.look_crashed to R.string.look_crashed_body
                }
                // Running the same photo again only helps when the model was missing or the cutout crashed.
                val retryable = state.why == Segmentation.Failure.ModelUnavailable || state.why == Segmentation.Failure.Crashed
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = colors.surfaceContainerLowest,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource(R.drawable.ic_ed_content_cut), contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(stringResource(title), style = MaterialTheme.typography.titleMedium.copy(fontFamily = Bricolage, fontWeight = FontWeight.Bold))
                        }
                        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            // A saved look must stay reversible even when the cutout can't run. Close is also top left.
                            if (state.sticker.look != null) {
                                TextButton(onClick = onUseOriginal) { Text(stringResource(R.string.look_use_original)) }
                            } else {
                                TextButton(onClick = onClose) { Text(stringResource(R.string.look_close)) }
                            }
                            if (retryable) {
                                Button(onClick = onRetry, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                    Text(stringResource(R.string.editor_try_again))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One style in the picker row: a square thumbnail over its [label]. With [image] null it is an
 * empty placeholder. [crop] fills the square (for the photo); looks are fitted with room around them.
 */
@Composable
private fun RowScope.StyleTile(
    label: Int,
    image: Bitmap?,
    crop: Boolean,
    selected: Boolean,
    sending: Boolean,
    onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val tileShape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .weight(1f)
            .then(
                if (onClick != null) Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                else Modifier
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(tileShape)
                .background(colors.surfaceContainer)
                .then(if (selected) Modifier.border(3.dp, colors.primary, tileShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    modifier = if (crop) Modifier.fillMaxSize() else Modifier.fillMaxSize(0.74f),
                    contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                )
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(colors.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
            if (sending) {
                Text(
                    stringResource(R.string.look_sending).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.03.em,
                    ),
                    color = colors.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(colors.surfaceContainerLowest, RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                )
            }
        }
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

private const val TAG = "stickerboard"
private const val MIN_SEGMENT_SIDE = 512

private fun moduleStillDownloading(error: Throwable): Boolean {
    var cur: Throwable? = error
    while (cur != null) {
        val msg = cur.message.orEmpty()
        if (msg.contains("Please wait", ignoreCase = true) ||
            msg.contains("optional module", ignoreCase = true)
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
}

private fun <T> awaitIo(task: Task<T>): T {
    try {
        return Tasks.await(task)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw CancellationException()
    }
}

private fun Bitmap.toRaster(): Raster {
    val src = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false) ?: this
    val pixels = IntArray(src.width * src.height)
    src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
    return Raster(src.width, src.height, pixels)
}

private fun Raster.toBitmap(): Bitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)

private fun Bitmap.atLeast(minSide: Int): Bitmap {
    val min = minOf(width, height)
    if (min >= minSide) return this
    val scale = minSide.toFloat() / min
    val w = (width * scale).roundToInt().coerceAtLeast(1)
    val h = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, w, h, true)
}
