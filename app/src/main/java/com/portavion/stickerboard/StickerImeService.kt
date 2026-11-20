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
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/**
 * The sticker keyboard: Favourites, Recents and Library tabs over a scrolling grid of the
 * selected shelf. Tapping a sticker sends it, then hands control back to the previous keyboard.
 */
class StickerImeService : InputMethodService() {
    private val store by lazy { StickerStore(this) }
    private val ranks by lazy { RankStore.of(this) }
    private val main = Handler(Looper.getMainLooper())
    private var grid = GridAdapter()
    // True from a tap until the next input view starts, so a double tap sends one sticker.
    private var sending = false
    private var list: RecyclerView? = null
    private val tabs = mutableMapOf<Shelf, ImageView>()
    private var shelves: Shelves<Sticker> = Shelves.empty()
    // Null until the first start picks a default, then the shelf the user last picked.
    private var shelf: Shelf? = null
    private var empty: View? = null
    private var emptyTitle: TextView? = null
    private var emptyBody: TextView? = null
    private var openApp: View? = null

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
        tabs.clear()
        for (target in Shelf.entries) {
            val tab = ImageView(this).apply {
                setImageResource(
                    when (target) {
                        Shelf.Favourites -> R.drawable.ic_shelf_favourites
                        Shelf.Recents -> R.drawable.ic_shelf_recents
                        Shelf.Library -> R.drawable.ic_shelf_library
                    }
                )
                contentDescription = getString(
                    when (target) {
                        Shelf.Favourites -> R.string.shelf_favourites
                        Shelf.Recents -> R.string.shelf_recents
                        Shelf.Library -> R.string.shelf_library
                    }
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(13), dp(6), dp(13), dp(6))
                setOnClickListener { show(target) }
            }
            tabs[target] = tab
            bar.addView(
                tab,
                LinearLayout.LayoutParams(dp(44), dp(30)).apply { marginStart = dp(8) }
            )
        }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))

        val body = FrameLayout(this)
        body.addView(
            RecyclerView(this).apply {
                list = this
                adapter = grid
                layoutManager = GridLayoutManager(context, MIN_COLUMNS)
                setPadding(dp(7), dp(4), dp(7), dp(8))
                clipToPadding = false
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
                    emptyTitle = this
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
                    emptyBody = this
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
                    openApp = this
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
        (list?.layoutManager as? GridLayoutManager)?.spanCount = columns
        shelves = ranks.shelves(store)
        val tabVisibility = if (shelves.libraryEmpty) View.GONE else View.VISIBLE
        tabs.values.forEach { it.visibility = tabVisibility }
        show(
            shelf ?: if (shelves.recents.isEmpty()) Shelf.Library else Shelf.Recents
        )
    }

    // Fills the grid with [next], marks its tab, and shows the empty state when it has nothing.
    private fun show(next: Shelf) {
        shelf = next
        val ink = getColor(R.color.sb_ink)
        for ((target, tab) in tabs) {
            val on = target == next
            tab.isSelected = on
            tab.background = pill(if (on) ink else Color.TRANSPARENT, ink)
            tab.imageTintList =
                ColorStateList.valueOf(getColor(if (on) R.color.sb_bg else R.color.sb_muted))
        }
        val stickers = shelves[next].map { it.item }
        grid.replace(stickers)
        list?.scrollToPosition(0)
        list?.visibility = if (stickers.isEmpty()) View.GONE else View.VISIBLE
        empty?.visibility = if (stickers.isEmpty()) View.VISIBLE else View.GONE
        val (title, body) = when {
            shelves.libraryEmpty -> R.string.kb_empty_title to R.string.kb_empty_body
            next == Shelf.Favourites -> R.string.ime_no_favourites to R.string.kb_no_favourites_body
            else -> R.string.ime_no_recents to R.string.kb_no_recents_body
        }
        emptyTitle?.setText(title)
        emptyBody?.setText(body)
        openApp?.visibility = if (shelves.libraryEmpty) View.VISIBLE else View.GONE
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

    private class Holder(view: View, val image: ImageView) : RecyclerView.ViewHolder(view)

    private inner class GridAdapter : RecyclerView.Adapter<Holder>() {
        private var stickers: List<Sticker> = emptyList()

        fun replace(next: List<Sticker>) {
            stickers = next
            notifyDataSetChanged()
        }

        override fun getItemCount() = stickers.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
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
            val sticker = stickers[position]
            holder.image.setImageBitmap(StickerStore.decode(sticker.sent))
            holder.itemView.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                insertOrCopy(sticker)
            }
            // A handled long click already plays the LONG_PRESS haptic.
            holder.itemView.setOnLongClickListener {
                copyToClipboard(sticker)
                Toast.makeText(this@StickerImeService, R.string.copied, Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    companion object {
        private const val MIN_COLUMNS = 4
        private const val CELL_DP = 88
        private const val SWITCH_BACK_MS = 120L
    }
}
