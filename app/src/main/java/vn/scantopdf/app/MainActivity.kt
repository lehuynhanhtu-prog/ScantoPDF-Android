package vn.scantopdf.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val photos = mutableListOf<Uri>()
    private val pageRotations = mutableMapOf<Uri, Int>()
    private val manualCorners = mutableMapOf<Uri, List<PointF>>()
    private lateinit var status: TextView
    private lateinit var mode: Spinner
    private lateinit var quality: Spinner
    private lateinit var autoCorrect: CheckBox
    private val PICK = 10
    private val SAVE = 11
    private val SAVE_CLOUD = 13
    private val CAMERA = 12
    private var pendingCameraUri: Uri? = null

    private val navy = Color.rgb(18, 59, 105)
    private val blue = Color.rgb(29, 104, 191)
    private val green = Color.rgb(25, 135, 84)
    private val bg = Color.rgb(245, 247, 251)
    private val card = Color.WHITE
    private val textPrimary = Color.rgb(28, 39, 55)
    private val textSecondary = Color.rgb(94, 108, 128)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DocumentProcessor.init()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(navy, 20f)
        }
        header.addView(TextView(this).apply {
            text = "ScantoPDF"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "Quét ảnh và tài liệu thành PDF"
            textSize = 15f
            setTextColor(Color.rgb(220, 232, 247))
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(header, fullWidth(bottom = 16))

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(card, 18f, strokeColor = Color.rgb(225, 231, 240))
        }
        statusCard.addView(TextView(this).apply {
            text = "TÀI LIỆU HIỆN TẠI"
            textSize = 12f
            setTextColor(textSecondary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        status = TextView(this).apply {
            text = "Chưa có trang nào"
            textSize = 20f
            setTextColor(textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }
        statusCard.addView(status)
        statusCard.addView(TextView(this).apply {
            text = "Chọn ảnh mới sẽ bắt đầu một PDF mới. Chụp ảnh sẽ thêm trang vào tài liệu hiện tại."
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(6), 0, 0)
        })
        content.addView(statusCard, fullWidth(bottom = 16))

        val sourceCard = sectionCard("Nguồn tài liệu")
        val pick = actionButton("Chọn ảnh mới từ máy / Cloud", blue)
        pick.setOnClickListener { openImageBrowser() }
        sourceCard.addView(pick, fullWidth(bottom = 10))

        val camera = actionButton("Chụp tài liệu / Thêm trang", navy)
        camera.setOnClickListener { captureDocumentPage() }
        sourceCard.addView(camera, fullWidth(bottom = 10))

        val newScan = actionButton("Xóa trang hiện tại / Tạo scan mới", Color.rgb(108, 117, 125))
        newScan.setOnClickListener {
            if (photos.isEmpty()) {
                resetScanSession(showMessage = true)
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Tạo scan mới?")
                    .setMessage("Toàn bộ trang đang chọn sẽ bị xóa khỏi phiên hiện tại.")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Tạo mới") { _, _ -> resetScanSession(showMessage = true) }
                    .show()
            }
        }
        sourceCard.addView(newScan, fullWidth())
        content.addView(sourceCard, fullWidth(bottom = 16))

        val outputCard = sectionCard("Thiết lập PDF")
        outputCard.addView(fieldLabel("Chế độ màu"))
        mode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Màu", "Xám", "Trắng đen (B&W)")
            )
            background = rounded(Color.rgb(248, 250, 253), 12f, strokeColor = Color.rgb(218, 225, 235))
            setPadding(dp(12), 0, dp(12), 0)
        }
        outputCard.addView(mode, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { bottomMargin = dp(12) })

        outputCard.addView(fieldLabel("Chất lượng PDF"))
        quality = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Nhẹ", "Cân bằng", "Sắc nét")
            )
            setSelection(1)
            background = rounded(Color.rgb(248, 250, 253), 12f, strokeColor = Color.rgb(218, 225, 235))
            setPadding(dp(12), 0, dp(12), 0)
        }
        outputCard.addView(quality, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { bottomMargin = dp(10) })

        autoCorrect = CheckBox(this).apply {
            text = "Tự động cắt mép và nắn thẳng tài liệu"
            textSize = 14f
            setTextColor(textPrimary)
            isChecked = true
            setPadding(0, dp(4), 0, 0)
        }
        outputCard.addView(autoCorrect, fullWidth())
        outputCard.addView(TextView(this).apply {
            text = "Tự tìm 4 góc giấy, bỏ nền thừa và chỉnh phối cảnh. Có thể chỉnh 4 góc thủ công ở bước kiểm tra."
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(dp(4), dp(2), 0, 0)
        }, fullWidth())
        content.addView(outputCard, fullWidth(bottom = 16))

        val export = actionButton("Tạo và lưu PDF", green).apply {
            textSize = 17f
            minHeight = dp(58)
        }
        export.setOnClickListener {
            if (photos.isEmpty()) {
                toast("Hãy chọn ảnh hoặc chụp tài liệu trước")
                return@setOnClickListener
            }
            showPageReviewDialog()
        }
        content.addView(export, fullWidth(bottom = 12))

        content.addView(TextView(this).apply {
            text = "PDF tự xoay A4 ngang/dọc theo từng ảnh và giữ đúng tỷ lệ."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(textSecondary)
        }, fullWidth())

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            setBackgroundColor(bg)
        })

        if (intent?.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { photos.add(it) }
        } else if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { photos.addAll(it) }
        }
        refresh()
    }

    private fun sectionCard(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = rounded(card, 18f, strokeColor = Color.rgb(225, 231, 240))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                setTextColor(textPrimary)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(12))
            })
        }
    }

    private fun fieldLabel(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, 0, 0, dp(6))
        }
    }

    private fun actionButton(label: String, color: Int): Button {
        return Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(color, 14f)
            minHeight = dp(52)
            stateListAnimator = null
        }
    }

    private fun fullWidth(bottom: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(bottom) }
    }

    private fun rounded(fillColor: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun captureDocumentPage() {
        val captureDir = File(cacheDir, "captured").apply { mkdirs() }
        val photoFile = File.createTempFile("page_", ".jpg", captureDir)
        val uri = FileProvider.getUriForFile(
            this,
            "vn.scantopdf.app.fileprovider",
            photoFile
        )
        pendingCameraUri = uri

        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("ScantoPDF capture", uri)
        }

        if (cameraIntent.resolveActivity(packageManager) != null) {
            startActivityForResult(cameraIntent, CAMERA)
        } else {
            pendingCameraUri = null
            photoFile.delete()
            toast("Không tìm thấy ứng dụng máy ảnh")
        }
    }

    private fun openImageBrowser() {
        val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
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
        startActivityForResult(pickerIntent, PICK)
    }

    private fun addPhoto(uri: Uri) {
        val type = contentResolver.getType(uri)
        if (type != null && !type.startsWith("image/")) return
        if (!photos.contains(uri)) {
            photos.add(uri)
            pageRotations[uri] = 0
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    private fun resetScanSession(showMessage: Boolean = false) {
        photos.clear()
        pageRotations.clear()
        manualCorners.clear()
        pendingCameraUri = null
        File(cacheDir, "captured").listFiles()?.forEach { it.delete() }
        refresh()
        if (showMessage) toast("Đã bắt đầu tài liệu mới")
    }

    private fun showCreatingDialog(): AlertDialog {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(32), dp(36), dp(32))
        }
        box.addView(ProgressBar(this).apply { isIndeterminate = true })
        box.addView(TextView(this).apply {
            text = "Đang tạo PDF...\nVui lòng không đóng ứng dụng."
            textSize = 18f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })

        return AlertDialog.Builder(this)
            .setTitle("Đang xử lý tài liệu")
            .setView(box)
            .setCancelable(false)
            .create()
    }

    private fun savedFileDescription(uri: Uri): String {
        var name = "ScantoPDF.pdf"
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index)
                }
            }
        } catch (_: Exception) {
        }

        val authority = uri.authority.orEmpty()
        val documentId = try {
            if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
                android.provider.DocumentsContract.getDocumentId(uri)
            } else null
        } catch (_: Exception) {
            null
        }

        val path = when {
            documentId != null && authority == "com.android.externalstorage.documents" -> {
                val parts = documentId.split(":", limit = 2)
                if (parts.size == 2) {
                    val volume = parts[0]
                    val relative = parts[1]
                    if (volume.equals("primary", ignoreCase = true)) {
                        "/storage/emulated/0/$relative"
                    } else {
                        "/storage/$volume/$relative"
                    }
                } else {
                    documentId
                }
            }
            documentId != null && authority.contains("downloads", ignoreCase = true) -> {
                val cleanId = documentId.removePrefix("raw:")
                if (cleanId.startsWith("/")) cleanId else "/storage/emulated/0/Download/$name"
            }
            authority.contains("drive", ignoreCase = true) ||
                authority.contains("docs", ignoreCase = true) ||
                authority.contains("onedrive", ignoreCase = true) -> {
                "Cloud: $authority / ${documentId ?: name}"
            }
            documentId != null -> "$authority / $documentId"
            uri.scheme == "file" -> uri.path ?: uri.toString()
            else -> uri.toString()
        }

        return "Đã tạo PDF thành công\n\nTên file: $name\nĐường dẫn: $path"
    }

    private fun startSavePdf(requestCode: Int) {
        if (requestCode == SAVE_CLOUD) {
            val treeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            AlertDialog.Builder(this)
                .setTitle("Chọn thư mục Cloud")
                .setMessage(
                    "Chọn thư mục trong Google Drive, OneDrive hoặc nhà cung cấp Cloud. " +
                        "Nếu chưa thấy Cloud, mở menu vị trí lưu của Android."
                )
                .setPositiveButton("Chọn thư mục") { _, _ ->
                    startActivityForResult(treeIntent, SAVE_CLOUD)
                }
                .setNegativeButton("Hủy", null)
                .show()
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "ScantoPDF.pdf")
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, SAVE)
    }

    private fun chooseSaveDestination() {
        AlertDialog.Builder(this)
            .setTitle("Chọn nơi lưu PDF")
            .setItems(arrayOf("Lưu trên máy", "Lưu lên Cloud (Drive / OneDrive)")) { _, which ->
                when (which) {
                    0 -> startSavePdf(SAVE)
                    1 -> startSavePdf(SAVE_CLOUD)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
    private fun showPageReviewDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        lateinit var dialog: AlertDialog

        val createPdf = actionButton("Tạo file PDF", green).apply {
            textSize = 17f
            minHeight = dp(58)
        }
        container.addView(createPdf, fullWidth(bottom = 10))

        val rotateAll = actionButton("Xoay tất cả 90°", blue)
        container.addView(rotateAll, fullWidth(bottom = 12))

        val previews = mutableListOf<ImageView>()

        photos.forEachIndexed { index, uri ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(10), dp(10), dp(12))
                background = rounded(Color.WHITE, 14f, strokeColor = Color.rgb(224, 230, 238))
            }

            row.addView(TextView(this).apply {
                text = "Trang ${index + 1}"
                textSize = 15f
                setTextColor(textPrimary)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, fullWidth(bottom = 8))

            val image = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.rgb(245, 247, 250))
            }
            updatePreviewImage(image, uri)
            previews.add(image)

            row.addView(
                image,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(220)
                ).apply { bottomMargin = dp(8) }
            )

            val editCorners = actionButton("Chỉnh 4 góc / Cắt mép", blue)
            editCorners.setOnClickListener {
                showCornerEditor(uri, image)
            }
            row.addView(editCorners, fullWidth(bottom = 8))

            val rotateOne = actionButton("Xoay trang 90°", navy)
            rotateOne.setOnClickListener {
                pageRotations[uri] = ((pageRotations[uri] ?: 0) + 90) % 360
                updatePreviewImage(image, uri)
            }
            row.addView(rotateOne, fullWidth())

            container.addView(row, fullWidth(bottom = 10))
        }

        rotateAll.setOnClickListener {
            photos.forEachIndexed { index, uri ->
                pageRotations[uri] = ((pageRotations[uri] ?: 0) + 90) % 360
                updatePreviewImage(previews[index], uri)
            }
        }

        val createPdfBottom = actionButton("Tạo file PDF", green).apply {
            textSize = 17f
            minHeight = dp(58)
        }
        container.addView(createPdfBottom, fullWidth(bottom = 4))

        val scroll = ScrollView(this).apply { addView(container) }

        dialog = AlertDialog.Builder(this)
            .setTitle("Kiểm tra hướng các trang")
            .setMessage("Xem lại từng ảnh trước khi tạo PDF. Có thể xoay riêng từng trang hoặc xoay tất cả.")
            .setView(scroll)
            .setNegativeButton("Quay lại", null)
            .create()

        val proceed = {
            dialog.dismiss()
            chooseSaveDestination()
        }
        createPdf.setOnClickListener { proceed() }
        createPdfBottom.setOnClickListener { proceed() }

        dialog.show()
    }

    private fun updatePreviewImage(image: ImageView, uri: Uri) {
        try {
            val base = loadPreviewBitmap(uri)
            val corrected = when {
                manualCorners[uri] != null ->
                    DocumentProcessor.warpNormalized(base, manualCorners[uri]!!)
                autoCorrect.isChecked ->
                    DocumentProcessor.autoCorrect(base)
                else -> base
            }
            val rotated = rotateForPdf(corrected, pageRotations[uri] ?: 0)
            image.rotation = 0f
            image.setImageBitmap(rotated)
        } catch (_: Exception) {
            image.setImageDrawable(null)
        }
    }

    private fun showCornerEditor(uri: Uri, preview: ImageView) {
        try {
            val bitmap = loadPreviewBitmap(uri)
            val initial = manualCorners[uri]
                ?: DocumentProcessor.detectNormalizedCorners(bitmap)
                ?: listOf(
                    PointF(0.04f, 0.04f),
                    PointF(0.96f, 0.04f),
                    PointF(0.96f, 0.96f),
                    PointF(0.04f, 0.96f)
                )

            val editor = CornerAdjustView(this, bitmap, initial).apply {
                minimumHeight = dp(480)
            }

            val dialog = AlertDialog.Builder(this)
                .setTitle("Chỉnh 4 góc tài liệu")
                .setMessage("Kéo 4 nút tròn vào đúng 4 góc trang giấy. App sẽ cắt phần thừa và nắn ảnh thành hình chữ nhật.")
                .setView(editor)
                .setNegativeButton("Hủy", null)
                .setNeutralButton("Dùng tự động", null)
                .setPositiveButton("Áp dụng", null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    manualCorners[uri] = editor.normalizedCorners()
                    updatePreviewImage(preview, uri)
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    manualCorners.remove(uri)
                    updatePreviewImage(preview, uri)
                    dialog.dismiss()
                }
            }
            dialog.show()
        } catch (err: Exception) {
            toast("Không thể mở chỉnh góc: " + err.message)
        }
    }
    private fun loadPreviewBitmap(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 700) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("Ảnh không hợp lệ")
        return applyExifOrientation(uri, decoded)
    }

    private fun applyExifOrientation(uri: Uri, decoded: Bitmap): Bitmap {
        val orientation = try {
            contentResolver.openInputStream(uri)!!.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.preScale(-1f, 1f)
                matrix.postRotate(270f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.preScale(-1f, 1f)
                matrix.postRotate(90f)
            }
        }
        if (matrix.isIdentity) return decoded
        val corrected = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (corrected !== decoded) decoded.recycle()
        return corrected
    }

    private fun rotateForPdf(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
    private fun refresh() {
        status.text = when (photos.size) {
            0 -> "Chưa có trang nào"
            1 -> "1 trang đã sẵn sàng"
            else -> "${photos.size} trang đã sẵn sàng"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onBackPressed() {
        val message = if (photos.isNotEmpty()) {
            "Bạn đang có ${photos.size} trang chưa xuất PDF. Thoát ứng dụng sẽ bỏ phiên làm việc hiện tại."
        } else {
            "Bạn có chắc muốn thoát ScantoPDF?"
        }
        AlertDialog.Builder(this)
            .setTitle("Xác nhận thoát")
            .setMessage(message)
            .setNegativeButton("Ở lại", null)
            .setPositiveButton("Thoát") { _, _ -> super.onBackPressed() }
            .show()
    }

    @Deprecated("Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        if (requestCode == CAMERA) {
            pendingCameraUri?.let { addPhoto(it) }
            pendingCameraUri = null
            refresh()
            toast("Đã thêm một trang chụp")
            return
        }

        if (requestCode == PICK) {
            resetScanSession(showMessage = false)
            data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    addPhoto(clip.getItemAt(i).uri)
                }
            } ?: data?.data?.let { addPhoto(it) }
            refresh()
            toast("Đã bắt đầu PDF mới với ${photos.size} ảnh")
            return
        }

        if (requestCode == SAVE_CLOUD) {
            val treeUri = data?.data ?: return
            try {
                val flags = data.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(treeUri, flags)

                val treeDocumentId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val directoryUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    treeDocumentId
                )
                val target = android.provider.DocumentsContract.createDocument(
                    contentResolver,
                    directoryUri,
                    "application/pdf",
                    "ScantoPDF.pdf"
                ) ?: error("Không thể tạo file trong thư mục Cloud đã chọn")

                createPdfAt(target)
            } catch (e: Exception) {
                toast("Không thể lưu lên Cloud: ${e.message}")
            }
            return
        }

        if (requestCode == SAVE) {
            val target = data?.data ?: return
            try {
                val flags = data.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(target, flags)
            } catch (_: Exception) {
            }
            createPdfAt(target)
            return
        }
    }

    private fun createPdfAt(target: Uri) {
        val pageUris = photos.toList()
        val rotations = pageRotations.toMap()
        val corners = manualCorners.toMap()
        val useAutoCorrect = autoCorrect.isChecked
        val dialog = showCreatingDialog()
        dialog.show()
        status.text = "Đang tạo PDF..."

        Thread {
            try {
                val pdf = PdfDocument()
                for ((index, uri) in pageUris.withIndex()) {
                    runOnUiThread {
                        status.text = "Đang tạo trang ${index + 1}/${pageUris.size}"
                    }
                    val bitmap = loadBitmap(uri)
                    val corrected = when {
                        corners[uri] != null ->
                            DocumentProcessor.warpNormalized(bitmap, corners[uri]!!)
                        useAutoCorrect ->
                            DocumentProcessor.autoCorrect(bitmap)
                        else -> bitmap
                    }
                    val processed = process(corrected)
                    val manualRotation = ((rotations[uri] ?: 0) % 360 + 360) % 360
                    val finalBitmap = rotateForPdf(processed, manualRotation)

                    val pageLandscape = finalBitmap.width > finalBitmap.height
                    val w = if (pageLandscape) 842 else 595
                    val h = if (pageLandscape) 595 else 842
                    val page = pdf.startPage(
                        PdfDocument.PageInfo.Builder(w, h, index + 1).create()
                    )
                    val scale = min(
                        w.toFloat() / finalBitmap.width,
                        h.toFloat() / finalBitmap.height
                    )
                    val dw = finalBitmap.width * scale
                    val dh = finalBitmap.height * scale
                    val rect = android.graphics.RectF(
                        (w - dw) / 2,
                        (h - dh) / 2,
                        (w + dw) / 2,
                        (h + dh) / 2
                    )
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(
                        finalBitmap,
                        null,
                        rect,
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                    pdf.finishPage(page)
                    if (finalBitmap !== processed) finalBitmap.recycle()
                    if (processed !== corrected) processed.recycle()
                    if (corrected !== bitmap) corrected.recycle()
                    bitmap.recycle()
                }

                contentResolver.openOutputStream(target)?.use { pdf.writeTo(it) }
                    ?: error("Không thể mở tệp đầu ra")
                pdf.close()

                runOnUiThread {
                    dialog.dismiss()
                    resetScanSession(showMessage = false)
                    status.text = "Đã tạo PDF thành công"
                    AlertDialog.Builder(this)
                        .setTitle("Đã tạo PDF thành công")
                        .setMessage(savedFileDescription(target))
                        .setPositiveButton("Đóng", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dialog.dismiss()
                    status.text = "Lỗi: ${e.message}"
                    toast("Không thể tạo PDF")
                }
            }
        }.start()
    }
    private fun loadBitmap(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val maxDimension = when (quality.selectedItemPosition) {
            0 -> 1300
            1 -> 1900
            else -> 2600
        }
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("Ảnh không hợp lệ")
        return applyExifOrientation(uri, decoded)
    }
    private fun process(source: Bitmap): Bitmap {
        val selected = mode.selectedItemPosition
        if (selected == 0) return source

        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val pixels = IntArray(width * result.height)
        result.getPixels(pixels, 0, width, 0, 0, width, result.height)

        val histogram = IntArray(256)
        for (p in pixels) {
            val gray = (
                Color.red(p) * 299 +
                    Color.green(p) * 587 +
                    Color.blue(p) * 114
                ) / 1000
            histogram[gray]++
        }

        val total = pixels.size
        var sum = 0.0
        for (i in 0..255) sum += i * histogram[i]

        var background = 0
        var foreground = 0.0
        var best = -1.0
        var threshold = 127

        for (i in 0..255) {
            background += histogram[i]
            if (background == 0) continue
            val front = total - background
            if (front == 0) break

            foreground += i * histogram[i]
            val diff = foreground / background - (sum - foreground) / front
            val score = background.toDouble() * front * diff * diff
            if (score > best) {
                best = score
                threshold = i
            }
        }

        for (i in pixels.indices) {
            val p = pixels[i]
            var gray = (
                Color.red(p) * 299 +
                    Color.green(p) * 587 +
                    Color.blue(p) * 114
                ) / 1000
            if (selected == 2) gray = if (gray > threshold) 255 else 0
            pixels[i] = Color.rgb(gray, gray, gray)
        }

        result.setPixels(pixels, 0, width, 0, 0, width, result.height)
        return result
    }
}
