package vn.scantopdf.app

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.*
import kotlin.math.min
import kotlin.math.max

class MainActivity : Activity() {
    private val photos = mutableListOf<Uri>()
    private lateinit var status: TextView
    private lateinit var mode: Spinner
    private lateinit var quality: Spinner
    private val PICK = 10
    private val SAVE = 11

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
        }
        val heading = TextView(this).apply { text = "ScantoPDF"; textSize = 28f; setTextColor(Color.rgb(22,75,125)) }
        layout.addView(heading)
        status = TextView(this).apply { text = "Chưa chọn ảnh"; textSize = 16f }
        layout.addView(status)
        val pick = Button(this).apply { text = "Duyệt ảnh từ thư mục / Cloud Drive" }
        pick.setOnClickListener { openImageBrowser() }
        layout.addView(pick)
        mode = Spinner(this)
        mode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Màu", "Xám", "Trắng đen (B&W)"))
        layout.addView(TextView(this).apply { text = "Chế độ xuất" })
        layout.addView(mode)
        quality = Spinner(this)
        quality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Nhẹ", "Cân bằng", "Sắc nét"))
        quality.setSelection(1)
        layout.addView(TextView(this).apply { text = "Chất lượng PDF" })
        layout.addView(quality)
        val export = Button(this).apply { text = "Xuất PDF / Lưu vào Drive, OneDrive, máy" }
        export.setOnClickListener {
            if (photos.isEmpty()) { toast("Hãy chọn ảnh trước"); return@setOnClickListener }
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
                putExtra(Intent.EXTRA_TITLE, "ScantoPDF.pdf")
            }, SAVE)
        }
        layout.addView(export)
        val help = TextView(this).apply {
            text = "Có thể duyệt ảnh từ bất kỳ thư mục nào trên máy hoặc nhà cung cấp tệp như Google Drive/OneDrive đã cài. Bộ chọn chỉ hiển thị file ảnh. Có thể mở lại nhiều lần để thêm ảnh từ các thư mục khác nhau."
        }
        layout.addView(help)
        setContentView(ScrollView(this).apply { addView(layout) })
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { photos.add(it) }
        } else if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { photos.addAll(it) }
        }
        refresh()
    }
    private fun openImageBrowser() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "image/jpeg",
                    "image/png",
                    "image/webp",
                    "image/heic",
                    "image/heif",
                    "image/bmp",
                    "image/gif",
                    "image/tiff"
                )
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK)
    }

    private fun addPhoto(uri: Uri) {
        val type = contentResolver.getType(uri)
        if (type != null && !type.startsWith("image/")) return
        if (!photos.contains(uri)) photos.add(uri)
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }

    private fun refresh() { status.text = "Đã chọn ${photos.size} ảnh" }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    @Deprecated("Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == PICK) {
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) addPhoto(clip.getItemAt(i).uri)
            } ?: data.data?.let { addPhoto(it) }
            refresh()
        }
        if (requestCode == SAVE) {
            val target = data.data ?: return
            status.text = "Đang tạo PDF..."
            Thread {
                try {
                    val pdf = PdfDocument()
                    for ((index, uri) in photos.withIndex()) {
                        val bitmap = loadBitmap(uri)
                        val processed = process(bitmap)
                        val w = 595; val h = 842
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(w,h,index+1).create())
                        val scale = min(w.toFloat()/processed.width,h.toFloat()/processed.height)
                        val dw = processed.width * scale; val dh = processed.height * scale
                        val rect = android.graphics.RectF((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2)
                        page.canvas.drawColor(Color.WHITE)
                        page.canvas.drawBitmap(processed,null,rect,Paint(Paint.FILTER_BITMAP_FLAG))
                        pdf.finishPage(page)
                        if (processed !== bitmap) processed.recycle()
                        bitmap.recycle()
                    }
                    contentResolver.openOutputStream(target)?.use { pdf.writeTo(it) }
                        ?: error("Không thể mở tệp đầu ra")
                    pdf.close()
                    runOnUiThread { status.text = "Đã lưu PDF"; toast("Tạo PDF thành công") }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Lỗi: ${e.message}"; toast("Không thể tạo PDF") }
                }
            }.start()
        }
    }
    private fun loadBitmap(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it,null,bounds) }
        var sample = 1
        val maxDimension = when (quality.selectedItemPosition) { 0 -> 1300; 1 -> 1900; else -> 2600 }
        while (max(bounds.outWidth,bounds.outHeight)/sample > maxDimension*2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it,null,opts) } ?: error("Ảnh không hợp lệ")
    }
    private fun process(source: Bitmap): Bitmap {
        val selected = mode.selectedItemPosition
        if (selected == 0) return source
        val result = source.copy(Bitmap.Config.ARGB_8888,true)
        val width = result.width
        val pixels = IntArray(width * result.height)
        result.getPixels(pixels,0,width,0,0,width,result.height)
        val histogram = IntArray(256)
        for (p in pixels) {
            val gray = (Color.red(p)*299 + Color.green(p)*587 + Color.blue(p)*114)/1000
            histogram[gray]++
        }
        val total = pixels.size
        var sum = 0.0
        for (i in 0..255) sum += i * histogram[i]
        var background = 0; var foreground = 0.0; var best = -1.0; var threshold = 127
        for (i in 0..255) {
            background += histogram[i]
            if (background == 0) continue
            val front = total - background
            if (front == 0) break
            foreground += i * histogram[i]
            val diff = foreground/background - (sum-foreground)/front
            val score = background.toDouble()*front*diff*diff
            if (score > best) { best = score; threshold = i }
        }
        for (i in pixels.indices) {
            val p = pixels[i]
            var gray = (Color.red(p)*299 + Color.green(p)*587 + Color.blue(p)*114)/1000
            if (selected == 2) gray = if (gray > threshold) 255 else 0
            pixels[i] = Color.rgb(gray,gray,gray)
        }
        result.setPixels(pixels,0,width,0,0,width,result.height)
        return result
    }
}
