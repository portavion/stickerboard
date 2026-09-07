package com.portavion.stickerboard

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

sealed interface Editor {
    val sticker: Sticker
    data class Segmenting(override val sticker: Sticker) : Editor
    data class Choosing(
        override val sticker: Sticker,
        val previews: Map<Look, Bitmap>,
        val chosen: Look,
        val currentSent: Bitmap? = null,
    ) : Editor
    data class Failed(override val sticker: Sticker, val why: Segmentation.Failure) : Editor
}

suspend fun startEditing(context: Context, sticker: Sticker): Editor = withContext(Dispatchers.IO) {
    val source = StickerStore.decode(sticker.original, maxPx = 1024)
        ?: return@withContext Editor.Failed(sticker, Segmentation.Failure.Unreadable)
    val currentSent = sticker.look?.let { StickerStore.decode(it, maxPx = 1024) }
    when (val seg = segment(context, source.atLeast(MIN_SEGMENT_SIDE))) {
        is Segmentation.Cut -> Editor.Choosing(
            sticker = sticker,
            previews = Look.all.associateWith { it.render(seg.raster).toBitmap() },
            chosen = Look.DieCut,
            currentSent = currentSent,
        )
        is Segmentation.Failure -> Editor.Failed(sticker, seg)
    }
}

suspend fun segment(context: Context, source: Bitmap): Segmentation = withContext(Dispatchers.IO) {
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

@Composable
fun EditorScreen(
    state: Editor,
    onPick: (Look) -> Unit,
    onSave: () -> Unit,
    onRevert: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Checkerboard(Modifier.fillMaxSize())
            when (state) {
                is Editor.Segmenting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is Editor.Choosing -> Image(
                    bitmap = state.previews.getValue(state.chosen).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentScale = ContentScale.Fit
                )
                is Editor.Failed -> Text(
                    stringResource(failureMessage(state.why)),
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
            }
        }
        if (state is Editor.Choosing && state.sticker.look != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.currentSent?.let { sent ->
                    Image(
                        bitmap = sent.asImageBitmap(),
                        contentDescription = stringResource(R.string.look_now_sending),
                        modifier = Modifier.size(48.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Text(stringResource(R.string.look_now_sending))
            }
        }
        if (state is Editor.Choosing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LookPickButton(
                    label = R.string.look_cutout,
                    selected = state.chosen == Look.Cutout,
                    onClick = { onPick(Look.Cutout) }
                )
                LookPickButton(
                    label = R.string.look_sticker,
                    selected = state.chosen == Look.DieCut,
                    onClick = { onPick(Look.DieCut) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state is Editor.Choosing) {
                Button(onClick = onSave) { Text(stringResource(R.string.look_save)) }
            }
            if (state.sticker.look != null) {
                FilledTonalButton(onClick = onRevert) { Text(stringResource(R.string.look_revert)) }
            }
            FilledTonalButton(onClick = onClose) { Text(stringResource(R.string.look_close)) }
        }
    }
}

@Composable
private fun LookPickButton(label: Int, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(stringResource(label)) }
    } else {
        FilledTonalButton(onClick = onClick) { Text(stringResource(label)) }
    }
}

@Composable
private fun Checkerboard(modifier: Modifier = Modifier) {
    val light = Color(0xFFE8E8E8)
    val dark = Color(0xFFC8C8C8)
    Canvas(modifier) {
        val tile = 8.dp.toPx()
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var col = 0
            while (x < size.width) {
                drawRect(
                    color = if ((row + col) and 1 == 0) light else dark,
                    topLeft = Offset(x, y),
                    size = Size(tile, tile)
                )
                x += tile
                col++
            }
            y += tile
            row++
        }
    }
}

private fun failureMessage(why: Segmentation.Failure): Int = when (why) {
    Segmentation.Failure.Unreadable -> R.string.look_unreadable
    Segmentation.Failure.NoSubject -> R.string.look_no_subject
    Segmentation.Failure.ModelUnavailable -> R.string.look_model_unavailable
    Segmentation.Failure.Crashed -> R.string.look_crashed
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
