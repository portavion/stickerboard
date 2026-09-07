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
    private var pages: PageAdapter? = null
    private var pager: ViewPager2? = null
    private var pageLabel: TextView? = null
    private var empty: TextView? = null

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
        bar.addView(
            TextView(this).apply {
                text = "stickerboard"
                setTextColor(Color.WHITE)
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
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
                        override fun onPageSelected(position: Int) = refreshPageLabel()
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
        val list = store.list()
        pages?.replace(list)
        empty?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        pager?.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        val n = pages?.itemCount ?: 0
        if (n == 0) pager?.setCurrentItem(0, false)
        else if ((pager?.currentItem ?: 0) >= n) pager?.setCurrentItem(n - 1, false)
        refreshPageLabel()
    }

    private fun refreshPageLabel() {
        val n = pages?.itemCount ?: 0
        pageLabel?.text = if (n > 1) "${(pager?.currentItem ?: 0) + 1} / $n" else ""
    }

    private fun insertOrCopy(sticker: Sticker) {
        val editorInfo = currentInputEditorInfo
        val ic = currentInputConnection
        val uri = store.contentUri(sticker)
        editorInfo?.packageName?.let {
            grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val accepted = editorInfo?.let { EditorInfoCompat.getContentMimeTypes(it) }.orEmpty()
        val canCommit = accepted.any { declared ->
            ClipDescription.compareMimeTypes(sticker.format.mime, declared) ||
                ClipDescription.compareMimeTypes("image/*", declared)
        }
        if (canCommit && ic != null && editorInfo != null) {
            val content = InputContentInfoCompat(
                uri,
                ClipDescription(sticker.file.name, arrayOf(sticker.format.mime)),
                null
            )
            val ok = InputConnectionCompat.commitContent(
                ic,
                editorInfo,
                content,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
            if (ok) {
                goBackToPreviousIme()
                return
            }
        }
        copyToClipboard(sticker)
        Toast.makeText(this, R.string.paste_from_clipboard, Toast.LENGTH_SHORT).show()
        goBackToPreviousIme()
    }

    private fun copyToClipboard(sticker: Sticker) {
        val uri = store.contentUri(sticker)
        currentInputEditorInfo?.packageName?.let {
            grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val clip = ClipData.newUri(contentResolver, sticker.file.name, uri)
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
                                    setImageBitmap(StickerStore.thumbnail(sticker.file))
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
