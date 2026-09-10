package com.portavion.stickerboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.max

class StickerImeService : InputMethodService() {
    private val store by lazy { StickerStore(this) }
    private val ranks by lazy { RankStore.of(this) }
    private var pages: PageAdapter? = null
    private var pager: ViewPager2? = null
    private var pageLabel: TextView? = null
    private var empty: TextView? = null
    private var favTab: Button? = null
    private var recentsTab: Button? = null
    private var shelves: Shelves<Sticker> = Shelves.empty()
    private var shelf: Shelf = Shelf.Recents
    private val pageOf = mutableMapOf(
        Shelf.Favourites to 0,
        Shelf.Recents to 0,
    )

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1C1C1E.toInt())
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        bar.addView(
            Button(this).apply {
                text = "Gboard"
                setOnClickListener { goBackToPreviousIme() }
            }
        )
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        favTab = tabButton(Shelf.Favourites).also { tabs.addView(it) }
        recentsTab = tabButton(Shelf.Recents).also { tabs.addView(it) }
        bar.addView(tabs)
        pageLabel = TextView(this).apply {
            setTextColor(0xFFAEAEB2.toInt())
            gravity = Gravity.END
        }
        bar.addView(pageLabel)
        root.addView(bar)

        val body = FrameLayout(this)
        val pageAdapter = PageAdapter().also { pages = it }
        body.addView(
            ViewPager2(this).apply {
                adapter = pageAdapter
                pager = this
                registerOnPageChangeCallback(
                    object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            pageOf[shelf] = position
                            refreshPageLabel()
                        }
                    }
                )
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        empty = TextView(this).apply {
            text = "Add stickers in the stickerboard app"
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        body.addView(empty)
        root.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
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
        val listed = store.list()
        shelves = if (listed.isEmpty()) Shelves.empty() else ranks.shelves(listed)
        show(shelf)
    }

    private fun tabButton(target: Shelf) = Button(this).apply {
        text = getString(shelfLabel(target))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener {
            if (shelf == target) return@setOnClickListener
            pageOf[shelf] = pager?.currentItem ?: 0
            show(target)
        }
    }

    private fun show(next: Shelf) {
        shelf = next
        markTab(favTab, next == Shelf.Favourites)
        markTab(recentsTab, next == Shelf.Recents)
        val items = shelves[next].map { it.item }
        pages?.replace(items)
        val emptyNow = items.isEmpty()
        empty?.text = if (shelves.libraryEmpty) {
            "Add stickers in the stickerboard app"
        } else {
            getString(R.string.ime_no_favourites)
        }
        empty?.visibility = if (emptyNow) View.VISIBLE else View.GONE
        pager?.visibility = if (emptyNow) View.GONE else View.VISIBLE
        val n = pages?.itemCount ?: 0
        val want = pageOf[next] ?: 0
        if (n == 0) pager?.setCurrentItem(0, false)
        else pager?.setCurrentItem(want.coerceIn(0, n - 1), false)
        refreshPageLabel()
    }

    private fun refreshPageLabel() {
        val n = pages?.itemCount ?: 0
        pageLabel?.text = if (n > 1) "${(pager?.currentItem ?: 0) + 1} / $n" else ""
    }

    private fun insertOrCopy(sticker: Sticker) {
        if (commitContent(sticker)) return sent(sticker)
        copyToClipboard(sticker)
        Toast.makeText(this, R.string.paste_from_clipboard, Toast.LENGTH_SHORT).show()
        sent(sticker)
    }

    private fun commitContent(sticker: Sticker): Boolean {
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
        if (!canCommit || ic == null || editorInfo == null) return false
        val content = InputContentInfoCompat(
            uri,
            ClipDescription(sticker.sent.file.name, arrayOf(sticker.sent.format.mime)),
            null
        )
        return InputConnectionCompat.commitContent(
            ic,
            editorInfo,
            content,
            InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            null
        )
    }

    private fun sent(sticker: Sticker) {
        ranks.recordSend(sticker)
        goBackToPreviousIme()
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

    private fun markTab(button: Button?, on: Boolean) {
        button?.isSelected = on
        button?.alpha = if (on) 1f else 0.45f
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shelfLabel(shelf: Shelf) = when (shelf) {
        Shelf.Favourites -> R.string.shelf_favourites
        Shelf.Recents -> R.string.shelf_recents
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        private var items: List<Sticker> = emptyList()

        fun replace(next: List<Sticker>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount() =
            if (items.isEmpty()) 0 else (items.size + PAGE_SIZE - 1) / PAGE_SIZE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val page = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return Holder(page)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items.drop(position * PAGE_SIZE).take(PAGE_SIZE))
        }

        inner class Holder(private val page: LinearLayout) : RecyclerView.ViewHolder(page) {
            fun bind(pageItems: List<Sticker>) {
                page.removeAllViews()
                repeat(ROWS) { r ->
                    val row = LinearLayout(page.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        )
                    }
                    repeat(COLS) { c ->
                        val cell = FrameLayout(page.context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                1f
                            )
                        }
                        pageItems.getOrNull(r * COLS + c)?.let { sticker ->
                            cell.addView(
                                ImageView(page.context).apply {
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                    setPadding(dp(6), dp(6), dp(6), dp(6))
                                    setImageBitmap(StickerStore.decode(sticker.sent))
                                    setOnClickListener { insertOrCopy(sticker) }
                                    setOnLongClickListener {
                                        copyToClipboard(sticker)
                                        Toast.makeText(
                                            this@StickerImeService,
                                            R.string.copied,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        true
                                    }
                                }
                            )
                        }
                        row.addView(cell)
                    }
                    page.addView(row)
                }
            }
        }
    }

    companion object {
        private const val COLS = 4
        private const val ROWS = 3
        private const val PAGE_SIZE = COLS * ROWS
    }
}
