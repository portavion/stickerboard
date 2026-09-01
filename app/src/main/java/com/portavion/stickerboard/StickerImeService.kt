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
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
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
import kotlin.math.max

class StickerImeService : InputMethodService() {
    private val store by lazy { StickerStore(this) }
    private var adapter: StickerAdapter? = null
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
            }
        )
        root.addView(bar)

        val body = FrameLayout(this)
        val gridAdapter = StickerAdapter().also { adapter = it }
        body.addView(
            GridView(this).apply {
                numColumns = 4
                adapter = gridAdapter
                onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, pos, _ ->
                    insertOrCopy(gridAdapter.getItem(pos))
                }
                onItemLongClickListener = android.widget.AdapterView.OnItemLongClickListener { _, _, pos, _ ->
                    copyToClipboard(gridAdapter.getItem(pos))
                    Toast.makeText(this@StickerImeService, R.string.copied, Toast.LENGTH_SHORT).show()
                    true
                }
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
        adapter?.replace(list)
        empty?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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
            ClipDescription.compareMimeTypes(sticker.mimeType, declared) ||
                ClipDescription.compareMimeTypes("image/*", declared)
        }
        if (canCommit && ic != null && editorInfo != null) {
            val content = InputContentInfoCompat(
                uri,
                ClipDescription(sticker.file.name, arrayOf(sticker.mimeType)),
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

    private inner class StickerAdapter : BaseAdapter() {
        private var items: List<Sticker> = emptyList()

        fun replace(next: List<Sticker>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].file.name.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? ImageView) ?: ImageView(parent.context).apply {
                val size = dp(72)
                layoutParams = ViewGroup.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            view.setImageBitmap(StickerStore.thumbnail(items[position].file))
            return view
        }
    }
}
