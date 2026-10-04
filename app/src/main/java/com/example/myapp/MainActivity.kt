package com.example.myapp

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

// Light item: only strings and a number. The document Uri is built later, only for the few images in use.
class ImgItem(val name: String, val docId: String, val num: Long) {
    val key: String = name.lowercase()
}

fun numOf(name: String): Long {
    val base = name.substringBeforeLast('.').trim()
    return base.toLongOrNull() ?: Long.MAX_VALUE
}

fun mimeFromName(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        else -> null
    }
}

// Serves the picked images to other apps (browser, chat apps) when they paste from the clipboard.
class ClipProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun docOf(uri: Uri): Uri? {
        val segs = uri.pathSegments
        if (segs.size < 1) return null
        return Uri.parse(segs[0])
    }

    override fun getType(uri: Uri): String? {
        val d = docOf(uri) ?: return null
        val ctx = context ?: return null
        val nm = uri.lastPathSegment ?: ""
        val t = ctx.contentResolver.getType(d)
        if (t != null && t.startsWith("image/")) return t
        return mimeFromName(nm) ?: t
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val d = docOf(uri) ?: return null
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: "image"
        var size = -1L
        try {
            ctx.contentResolver.query(d, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    size = c.getLong(0)
                }
            }
        } catch (e: Exception) {
        }
        val want: Array<String> = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any>(want.size)
        for (i in want.indices) {
            if (want[i] == OpenableColumns.DISPLAY_NAME) {
                row[i] = name
            } else if (want[i] == OpenableColumns.SIZE) {
                if (size >= 0L) row[i] = size
            }
        }
        val mc = MatrixCursor(want)
        mc.addRow(row)
        return mc
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val d = docOf(uri) ?: return null
        val ctx = context ?: return null
        return ctx.contentResolver.openFileDescriptor(d, "r")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}

class MainActivity : Activity() {

    companion object {
        const val REQ_TREE = 101
        const val AUTH = "com.example.myapp.clip"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var rootView: LinearLayout
    private lateinit var status: TextView
    private lateinit var batchEdit: EditText
    private lateinit var strip: LinearLayout
    private lateinit var stripScroll: HorizontalScrollView

    private var treeUri: Uri? = null
    private var files: List<ImgItem> = emptyList()
    private var batch: Int = 7
    private var pos: Int = 0
    private var sub: Int = 0
    private var lastMsg: String = ""
    private var lastError: String = ""
    private var loadToken: Int = 0
    private var thumbToken: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("imgbatch", Context.MODE_PRIVATE)
        batch = prefs.getInt("batch", 7)
        pos = prefs.getInt("pos", 0)
        sub = prefs.getInt("sub", 0)
        val saved = prefs.getString("tree", null)
        if (saved != null) {
            treeUri = Uri.parse(saved)
        }
        buildUi()
        if (treeUri != null) {
            reload()
        } else {
            refreshAll()
        }
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putInt("batch", batch).putInt("pos", pos).putInt("sub", sub).apply()
    }

    // ---------- UI ----------

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    private fun mkBtn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.isFocusable = false
        b.textSize = 13f
        b.setOnClickListener { onClick() }
        return b
    }

    private fun mkRow(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return r
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(8), dp(8), dp(8), dp(8))
        root.isFocusable = true
        root.isFocusableInTouchMode = true

        val pickB = mkBtn("Pick folder") { pickFolder() }
        batchEdit = EditText(this)
        batchEdit.inputType = InputType.TYPE_CLASS_NUMBER
        batchEdit.setText(batch.toString())
        batchEdit.gravity = Gravity.CENTER
        batchEdit.hint = "Batch"
        batchEdit.imeOptions = EditorInfo.IME_ACTION_DONE
        batchEdit.setOnEditorActionListener { _, _, _ ->
            applyBatchSize()
            true
        }
        val setB = mkBtn("Set size") { applyBatchSize() }
        root.addView(mkRow(pickB, batchEdit, setB))

        val copyB = mkBtn("Copy batch") { copyBatch() }
        val oneB = mkBtn("Copy one") { copyOne() }
        val shareB = mkBtn("Share batch") { shareBatch() }
        root.addView(mkRow(copyB, oneB, shareB))

        val prevB = mkBtn("Prev batch") { prevBatch() }
        val resetB = mkBtn("Reset to 1") { resetAll() }
        val refreshB = mkBtn("Refresh list") { reload() }
        root.addView(mkRow(prevB, resetB, refreshB))

        status = TextView(this)
        status.textSize = 15f
        status.setPadding(dp(4), dp(10), dp(4), dp(10))
        root.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        stripScroll = HorizontalScrollView(this)
        strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        stripScroll.addView(strip)
        root.addView(
            stripScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val hint = TextView(this)
        hint.textSize = 12f
        hint.setPadding(dp(4), dp(14), dp(4), dp(4))
        hint.text = "After Copy: open the other app, tap in the box, then paste " +
            "(long-press > Paste, or Ctrl+V).\n" +
            "Keys: C copy batch, O copy one, S share, B previous, R reset."
        root.addView(
            hint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        rootView = root
        setContentView(root)
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(batchEdit.windowToken, 0)
    }

    // ---------- Folder ----------

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, REQ_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null) {
            val u = data.data
            if (u != null) {
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
                treeUri = u
                prefs.edit().putString("tree", u.toString()).apply()
                files = emptyList()
                pos = 0
                sub = 0
                lastMsg = ""
                reload()
            }
        }
    }

    private fun cacheFile(): File? {
        val t = treeUri ?: return null
        return File(cacheDir, "list_" + t.toString().hashCode() + ".txt")
    }

    // Reads the saved folder list (already sorted). Fast even for tens of thousands of names.
    private fun loadCache(f: File): List<ImgItem> {
        val out = ArrayList<ImgItem>()
        try {
            if (!f.exists()) return out
            f.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val tab = line.indexOf('\t')
                    if (tab > 0) {
                        val id = line.substring(0, tab)
                        val name = line.substring(tab + 1)
                        out.add(ImgItem(name, id, numOf(name)))
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun saveCache(f: File, list: List<ImgItem>) {
        try {
            f.bufferedWriter().use { w ->
                for (e in list) {
                    w.write(e.docId)
                    w.write("\t")
                    w.write(e.name)
                    w.write("\n")
                }
            }
        } catch (ex: Exception) {
        }
    }

    private fun sameList(a: List<ImgItem>, b: List<ImgItem>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].docId != b[i].docId) return false
        }
        return true
    }

    private fun applyList(list: List<ImgItem>) {
        files = list
        if (pos >= files.size) {
            pos = 0
            sub = 0
        }
        refreshAll()
    }

    // The screen is usable at once from the saved list, then the real folder is read in the background.
    private fun reload() {
        val t = treeUri
        if (t == null) {
            refreshAll()
            return
        }
        loadToken += 1
        val token = loadToken
        val cf: File? = cacheFile()
        val needCache = files.isEmpty()
        if (needCache) {
            status.text = "Reading folder..."
        }
        Thread {
            if (needCache && cf != null) {
                val cached = loadCache(cf)
                if (cached.isNotEmpty()) {
                    runOnUiThread {
                        if (token == loadToken) {
                            applyList(cached)
                        }
                    }
                }
            }
            val list = listImages(t, token)
            val failed = lastError.isNotEmpty()
            runOnUiThread {
                if (token == loadToken) {
                    if (files.isEmpty() || !sameList(files, list)) {
                        applyList(list)
                    }
                }
            }
            if (cf != null && !failed) {
                saveCache(cf, list)
            }
        }.start()
    }

    private fun listImages(tree: Uri, token: Int): List<ImgItem> {
        val out = ArrayList<ImgItem>()
        lastError = ""
        try {
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            contentResolver.query(kids, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id: String? = c.getString(0)
                    val name: String = c.getString(1) ?: ""
                    val mime: String = c.getString(2) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (id != null && !isDir && (mime.startsWith("image/") || mimeFromName(name) != null)) {
                        out.add(ImgItem(name, id, numOf(name)))
                        if (out.size % 1000 == 0) {
                            val cnt = out.size
                            runOnUiThread {
                                if (token == loadToken && files.isEmpty()) {
                                    status.text = "Reading folder... " + cnt + " images found"
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            lastError = e.message ?: "error"
        }
        out.sortWith(Comparator<ImgItem> { a, b -> if (a.num != b.num) a.num.compareTo(b.num) else a.key.compareTo(b.key) })
        return out
    }

    private fun docUri(e: ImgItem): Uri {
        val t = treeUri ?: throw IllegalStateException("No folder")
        return DocumentsContract.buildDocumentUriUsingTree(t, e.docId)
    }

    // ---------- State / display ----------

    private fun label(e: ImgItem): String {
        return e.name.substringBeforeLast('.')
    }

    private fun currentBatch(): List<ImgItem> {
        if (pos >= files.size) return ArrayList<ImgItem>()
        val end = minOf(pos + batch, files.size)
        return ArrayList<ImgItem>(files.subList(pos, end))
    }

    private fun refreshAll() {
        strip.removeAllViews()
        thumbToken += 1
        val total = files.size
        if (treeUri == null) {
            status.text = "No folder chosen yet.\nTap \"Pick folder\" and choose the folder with your images."
            return
        }
        if (total == 0) {
            val extra = if (lastError.isEmpty()) "" else "\n(" + lastError + ")"
            status.text = "No images found in that folder." + extra
            return
        }
        if (pos >= total) {
            status.text = "Done: all " + total + " images used.\nTap \"Reset to 1\" to start again.\n\n" + lastMsg
            return
        }
        val end = minOf(pos + batch, total)
        val sb = StringBuilder()
        sb.append("Folder: ").append(total).append(" images   |   Batch size: ").append(batch).append("\n")
        sb.append("Next batch: ").append(label(files[pos])).append(" to ")
            .append(label(files[end - 1])).append("  (").append(end - pos).append(" images)\n")
        if (sub > 0 && pos + sub < end) {
            sb.append("Copy one: next is ").append(label(files[pos + sub])).append("\n")
        }
        if (lastMsg.isNotEmpty()) {
            sb.append("\n").append(lastMsg)
        }
        status.text = sb.toString()
        loadThumbs(pos, end)
    }

    private fun loadThumbs(from: Int, to: Int) {
        strip.removeAllViews()
        thumbToken += 1
        val myToken = thumbToken
        val shownEnd = minOf(to, from + 30)
        val snapshot = ArrayList<ImgItem>(files.subList(from, shownEnd))
        val thumbs = ArrayList<ImageView>()
        val docs = ArrayList<Uri>()
        for (e in snapshot) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setBackgroundColor(Color.LTGRAY)
            val lp = LinearLayout.LayoutParams(dp(96), dp(96))
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            strip.addView(iv, lp)
            thumbs.add(iv)
            docs.add(docUri(e))
        }
        Thread {
            for (k in docs.indices) {
                if (myToken != thumbToken) break
                val bmp: Bitmap? = decodeThumb(docs[k], 192)
                if (bmp != null) {
                    val iv = thumbs[k]
                    runOnUiThread {
                        if (myToken == thumbToken) {
                            iv.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }.start()
    }

    private fun decodeThumb(doc: Uri, target: Int): Bitmap? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            contentResolver.openInputStream(doc)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) {
                s *= 2
            }
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            return contentResolver.openInputStream(doc)?.use { BitmapFactory.decodeStream(it, null, o2) }
        } catch (e: Throwable) {
            return null
        }
    }

    // ---------- Actions ----------

    private fun applyBatchSize() {
        val n: Int? = batchEdit.text.toString().trim().toIntOrNull()
        if (n == null || n < 1) {
            toast("Enter a number, 1 or more")
            return
        }
        batch = n
        sub = 0
        lastMsg = "Batch size set to " + n + "."
        hideKeyboard()
        batchEdit.clearFocus()
        rootView.requestFocus()
        refreshAll()
    }

    private fun providerUri(e: ImgItem): Uri {
        return Uri.Builder()
            .scheme("content")
            .authority(AUTH)
            .appendPath(docUri(e).toString())
            .appendPath(e.name)
            .build()
    }

    private fun putOnClipboard(items: List<ImgItem>): Boolean {
        try {
            val uris = ArrayList<Uri>()
            for (e in items) {
                uris.add(providerUri(e))
            }
            val clip = ClipData.newUri(contentResolver, "images", uris[0])
            for (k in 1 until uris.size) {
                clip.addItem(contentResolver, ClipData.Item(uris[k]))
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            return true
        } catch (e: Exception) {
            toast("Copy failed: " + e.message)
            return false
        }
    }

    private fun copyBatch() {
        val items = currentBatch()
        if (items.isEmpty()) {
            toast("Nothing to copy. Pick a folder or tap Reset.")
            return
        }
        if (!putOnClipboard(items)) return
        lastMsg = "Copied " + label(items.first()) + " to " + label(items.last()) +
            " (" + items.size + " images). Now paste in the other app."
        pos += batch
        sub = 0
        refreshAll()
    }

    private fun copyOne() {
        val items = currentBatch()
        if (items.isEmpty()) {
            toast("Nothing to copy. Pick a folder or tap Reset.")
            return
        }
        if (sub >= items.size) sub = 0
        val e = items[sub]
        val one = ArrayList<ImgItem>()
        one.add(e)
        if (!putOnClipboard(one)) return
        sub += 1
        if (sub >= items.size) {
            lastMsg = "Copied " + label(e) + " (last one of this batch). Next batch is ready."
            pos += batch
            sub = 0
        } else {
            lastMsg = "Copied " + label(e) + " (" + sub + " of " + items.size + "). Now paste."
        }
        refreshAll()
    }

    private fun shareBatch() {
        val items = currentBatch()
        if (items.isEmpty()) {
            toast("Nothing to share. Pick a folder or tap Reset.")
            return
        }
        try {
            val uris = ArrayList<Uri>()
            for (e in items) {
                uris.add(providerUri(e))
            }
            val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
            if (uris.size == 1) {
                i.putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            i.type = "image/*"
            val clip = ClipData.newUri(contentResolver, "images", uris[0])
            for (k in 1 until uris.size) {
                clip.addItem(contentResolver, ClipData.Item(uris[k]))
            }
            i.clipData = clip
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share images"))
            lastMsg = "Shared " + label(items.first()) + " to " + label(items.last()) +
                " (" + items.size + " images)."
            pos += batch
            sub = 0
            refreshAll()
        } catch (e: Exception) {
            toast("Share failed: " + e.message)
        }
    }

    private fun prevBatch() {
        pos = maxOf(0, pos - batch)
        sub = 0
        lastMsg = "Went back one batch."
        refreshAll()
    }

    private fun resetAll() {
        pos = 0
        sub = 0
        lastMsg = "Reset to the start."
        refreshAll()
    }

    // ---------- Keyboard / mouse ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && currentFocus !is EditText) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_C -> {
                    copyBatch()
                    return true
                }
                KeyEvent.KEYCODE_O -> {
                    copyOne()
                    return true
                }
                KeyEvent.KEYCODE_S -> {
                    shareBatch()
                    return true
                }
                KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    prevBatch()
                    return true
                }
                KeyEvent.KEYCODE_R -> {
                    resetAll()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val d: Float = if (h != 0f) h else v
            stripScroll.scrollBy((-d * dp(60)).toInt(), 0)
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}
