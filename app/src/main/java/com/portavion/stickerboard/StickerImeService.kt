package com.portavion.stickerboard

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/**
 * The sticker keyboard: a Recent row above a scrolling grid of every sticker. Tapping one
 * sends it, then hands control back to the previous keyboard.
 */
class StickerImeService : InputMethodService() {
    private val store by lazy { StickerStore(this) }
    private val ranks by lazy { RankStore.of(this) }
    private val main = Handler(Looper.getMainLooper())
    private var grid = GridAdapter()
    // True from a tap until the next input view starts, so a double tap sends one sticker.
    private var sending = false
    private var list: RecyclerView? = null
    private var recentChip: TextView? = null
    private var allChip: TextView? = null
    private var empty: View? = null

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        // A fresh adapter per view: a shared one keeps every discarded RecyclerView alive.
        grid = GridAdapter()
        val ink = getColor(R.color.sb_ink)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.sb_keyboard))
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, dp(300))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        bar.addView(
            TextView(this).apply {
                setText(R.string.kb_abc)
                contentDescription = getString(R.string.kb_abc_description)
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setCompoundDrawablesRelative(icon(R.drawable.ic_kb_keyboard, ink), null, null, null)
                compoundDrawablePadding = dp(6)
                setPadding(dp(12), 0, dp(12), 0)
                background = pill(getColor(R.color.sb_surface), ink)
                setOnClickListener { goBackToPreviousIme() }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, dp(32))
        )
        recentChip = jumpChip(R.string.kb_recent) { jumpTo(0, recent = true) }
        allChip = jumpChip(R.string.kb_all) { jumpTo(grid.allStart, recent = false) }
        for (chip in listOf(recentChip, allChip)) {
            bar.addView(
                chip,
                LinearLayout.LayoutParams(WRAP_CONTENT, dp(30))
                    .apply { marginStart = dp(8) }
            )
        }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))

        val body = FrameLayout(this)
        body.addView(
            RecyclerView(this).apply {
                list = this
                adapter = grid
                layoutManager = GridLayoutManager(context, MIN_COLUMNS).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int) =
                            if (grid.rows[position] is Row.Label) spanCount else 1
                    }
                }
                setPadding(dp(7), 0, dp(7), dp(8))
                clipToPadding = false
                addOnScrollListener(
                    object : RecyclerView.OnScrollListener() {
                        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                            if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                            if (grid.allStart == 0) return
                            val first = (rv.layoutManager as GridLayoutManager)
                                .findFirstVisibleItemPosition()
                            // At the bottom, All is the section in view even if Recent still shows.
                            markActive(recent = first < grid.allStart && rv.canScrollVertically(1))
                        }
                    }
                )
            },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        )
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(40), 0, dp(40), dp(12))
            visibility = View.GONE
            addView(
                TextView(context).apply {
                    setText(R.string.kb_empty_title)
                    setTextColor(ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    typeface = Typeface.create(
                        ResourcesCompat.getFont(context, R.font.bricolage_grotesque),
                        Typeface.BOLD
                    )
                    gravity = Gravity.CENTER
                }
            )
            addView(
                TextView(context).apply {
                    setText(R.string.kb_empty_body)
                    setTextColor(getColor(R.color.sb_muted))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    WRAP_CONTENT,
                    WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
            val onAccent = getColor(R.color.sb_on_accent)
            addView(
                TextView(context).apply {
                    setText(R.string.kb_open_app)
                    setTextColor(onAccent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER_VERTICAL
                    setCompoundDrawablesRelative(
                        icon(R.drawable.ic_kb_open_in_new, onAccent), null, null, null
                    )
                    compoundDrawablePadding = dp(6)
                    setPadding(dp(16), 0, dp(16), 0)
                    background = pill(getColor(R.color.sb_accent), onAccent)
                    setOnClickListener {
                        startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, dp(38))
                    .apply { topMargin = dp(14) }
            )
        }
        body.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(
            body,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bottom = max(
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                dp(48)
            )
            v.setPadding(0, 0, 0, bottom)
            v.layoutParams = v.layoutParams.apply { height = dp(300) + bottom }
            insets
        }
        root.doOnAttach { ViewCompat.requestApplyInsets(it) }
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        sending = false
        val metrics = resources.displayMetrics
        val columns = max(MIN_COLUMNS, (metrics.widthPixels / metrics.density / CELL_DP).toInt())
        val all = store.list()
        // One row of recents keeps the All grid in view on a phone.
        val recents = ranks.shelves(store).recents.map { it.item }.take(columns)
        (list?.layoutManager as? GridLayoutManager)?.spanCount = columns
        grid.replace(recents, all)
        list?.scrollToPosition(0)
        list?.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        empty?.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        val chips = if (recents.isEmpty()) View.GONE else View.VISIBLE
        recentChip?.visibility = chips
        allChip?.visibility = chips
        markActive(recent = true)
    }

    private fun jumpChip(@StringRes text: Int, onClick: () -> Unit) =
        TextView(this).apply {
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
        }

    private fun jumpTo(position: Int, recent: Boolean) {
        markActive(recent)
        val scroller = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference() = SNAP_TO_START
        }
        scroller.targetPosition = position
        list?.layoutManager?.startSmoothScroll(scroller)
    }

    private fun markActive(recent: Boolean) {
        val ink = getColor(R.color.sb_ink)
        for ((chip, on) in listOf(recentChip to recent, allChip to !recent)) {
            chip?.background = pill(if (on) ink else Color.TRANSPARENT, ink)
            chip?.setTextColor(getColor(if (on) R.color.sb_bg else R.color.sb_muted))
        }
    }

    private fun insertOrCopy(sticker: Sticker) {
        if (sending) return
        sending = true
        val editorInfo = currentInputEditorInfo
        val ic = currentInputConnection
        val uri = store.contentUri(this, sticker)
        editorInfo?.packageName?.let {
            grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val accepted = editorInfo?.let { EditorInfoCompat.getContentMimeTypes(it) }.orEmpty()
        val canCommit = accepted.any { declared ->
            ClipDescription.compareMimeTypes(sticker.sent.format.mime, declared) ||
                ClipDescription.compareMimeTypes("image/*", declared)
        }
        val committed = canCommit && ic != null && editorInfo != null &&
            InputConnectionCompat.commitContent(
                ic,
                editorInfo,
                InputContentInfoCompat(
                    uri,
                    ClipDescription(sticker.sent.file.name, arrayOf(sticker.sent.format.mime)),
                    null
                ),
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
        if (!committed) {
            copyToClipboard(sticker)
            Toast.makeText(this, R.string.paste_from_clipboard, Toast.LENGTH_LONG).show()
        }
        ranks.recordSend(sticker)
        // Leave the pressed tile on screen for a beat so a hit reads as a hit.
        main.postDelayed(::goBackToPreviousIme, SWITCH_BACK_MS)
    }

    private fun copyToClipboard(sticker: Sticker) {
        val uri = store.contentUri(this, sticker)
        currentInputEditorInfo?.packageName?.let {
            grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val clip = ClipData.newUri(contentResolver, sticker.sent.file.name, uri)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun goBackToPreviousIme() {
        if (Build.VERSION.SDK_INT >= 28) {
            switchToPreviousInputMethod()
        } else {
            val token = window?.window?.attributes?.token
            getSystemService(InputMethodManager::class.java).switchToLastInputMethod(token)
        }
    }

    // A fully rounded fill that ripples in [ripple]. The mask keeps transparent fills rippling.
    private fun pill(fill: Int, ripple: Int): Drawable {
        val radius = dp(100).toFloat()
        return RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(ripple, 0x29)),
            GradientDrawable().apply { cornerRadius = radius; setColor(fill) },
            GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        )
    }

    private fun icon(res: Int, tint: Int): Drawable =
        getDrawable(res)!!.mutate().apply {
            setBounds(0, 0, dp(18), dp(18))
            setTint(tint)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private sealed interface Row {
        data class Label(@StringRes val text: Int) : Row
        data class Cell(val sticker: Sticker) : Row
    }

    private class Holder(view: View, val image: ImageView?) : RecyclerView.ViewHolder(view)

    private inner class GridAdapter : RecyclerView.Adapter<Holder>() {
        var rows: List<Row> = emptyList()
            private set

        /** Position of the All label, or 0 when there is no Recent section. */
        var allStart = 0
            private set

        fun replace(recents: List<Sticker>, all: List<Sticker>) {
            val head = if (recents.isEmpty()) emptyList() else
                listOf(Row.Label(R.string.kb_recent)) + recents.map(Row::Cell) +
                    Row.Label(R.string.kb_all)
            allStart = max(0, head.size - 1)
            rows = head + all.map(Row::Cell)
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) =
            if (rows[position] is Row.Label) LABEL else CELL

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == LABEL) {
                val label = TextView(parent.context).apply {
                    setTextColor(getColor(R.color.sb_muted))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    isAllCaps = true
                    letterSpacing = 0.06f
                    setPadding(dp(7), dp(10), dp(7), dp(6))
                }
                return Holder(label, image = null)
            }
            val cell = object : FrameLayout(parent.context) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    val height = (MeasureSpec.getSize(widthSpec) / 1.1f).toInt()
                    val exact = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                    super.onMeasure(widthSpec, exact)
                }
            }
            cell.layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
            cell.background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(ColorUtils.setAlphaComponent(getColor(R.color.sb_ink), 0x1F))
                    }
                )
                setExitFadeDuration(150)
            }
            val image = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isDuplicateParentStateEnabled = true
                stateListAnimator = StateListAnimator().apply {
                    val pressed = intArrayOf(android.R.attr.state_pressed)
                    for ((state, scale) in listOf(pressed to 0.88f, intArrayOf() to 1f)) {
                        addState(
                            state,
                            ObjectAnimator.ofPropertyValuesHolder(
                                PropertyValuesHolder.ofFloat(View.SCALE_X, scale),
                                PropertyValuesHolder.ofFloat(View.SCALE_Y, scale)
                            ).setDuration(90)
                        )
                    }
                }
            }
            cell.addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            return Holder(cell, image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = rows[position]) {
                is Row.Label -> (holder.itemView as TextView).setText(row.text)
                is Row.Cell -> {
                    holder.image?.setImageBitmap(StickerStore.decode(row.sticker.sent))
                    holder.itemView.setOnClickListener { v ->
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        insertOrCopy(row.sticker)
                    }
                    // A handled long click already plays the LONG_PRESS haptic.
                    holder.itemView.setOnLongClickListener {
                        copyToClipboard(row.sticker)
                        Toast.makeText(this@StickerImeService, R.string.copied, Toast.LENGTH_SHORT)
                            .show()
                        true
                    }
                }
            }
        }
    }

    companion object {
        private const val MIN_COLUMNS = 4
        private const val CELL_DP = 88
        private const val SWITCH_BACK_MS = 120L
        private const val LABEL = 0
        private const val CELL = 1
    }
}
