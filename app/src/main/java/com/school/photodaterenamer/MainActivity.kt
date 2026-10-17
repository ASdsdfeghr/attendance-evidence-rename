package com.school.photodaterenamer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Bảng màu lấy cảm hứng từ Logo NQD Volleyball Club (Đen tuyền - Đỏ cam - Kem ấm)
private val ClubBlack = Color(0xFF111111)
private val ClubDarkCard = Color(0xFF1B1B1B)
private val ClubOrangeRed = Color(0xFFE03C11)
private val ClubOrangeLight = Color(0xFFFFEDE8)
private val ClubCreamBg = Color(0xFFF5F0EE)
private val ClubSurface = Color(0xFFFFFFFF)
private val SchoolBlue = Color(0xFF0072BC)
private val SchoolPink = Color(0xFFD81B60)

private val NqdClubColorScheme = lightColorScheme(
    primary = ClubOrangeRed,
    onPrimary = Color.White,
    primaryContainer = ClubBlack,
    onPrimaryContainer = Color.White,
    secondary = ClubBlack,
    onSecondary = Color.White,
    secondaryContainer = ClubOrangeLight,
    onSecondaryContainer = ClubBlack,
    background = ClubCreamBg,
    surface = ClubSurface,
    onSurface = ClubBlack,
    surfaceVariant = Color(0xFFEBE3E0),
    onSurfaceVariant = Color(0xFF3E3A38)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContent {
                MaterialTheme(colorScheme = NqdClubColorScheme) {
                    PhotoRenamerScreen()
                }
            }
        } catch (t: Throwable) {
            val errorView = TextView(this).apply {
                text = "Startup Error:\n\n${t.stackTraceToString()}"
                setPadding(40, 40, 40, 40)
                textSize = 13f
            }
            setContentView(ScrollView(this).apply { addView(errorView) })
        }
    }
}

data class PhotoMetadataResult(
    val originalFileName: String,
    val extension: String,
    val mimeType: String,
    val captureDate: LocalDate?,
    val dateSource: String,
    val previewBitmap: Bitmap?
)

data class SaveResult(
    val savedUri: Uri,
    val finalFileName: String,
    val displayPath: String
)

data class SelectedPhotoItem(
    val index: Int,
    val uri: Uri,
    val metadata: PhotoMetadataResult,
    val chosenDate: LocalDate?,
    val dateSourceLabel: String
)

object PhotoUtils {

    const val SUBFOLDER_NAME = "Photo Date Renamer"

    private val exifDatePatterns = listOf(
        DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US)
    )

    fun formatDateAsDayMonth(date: LocalDate): String {
        return "${date.dayOfMonth}-${date.monthValue}"
    }

    fun buildFileName(date: LocalDate, extension: String, suffixNumber: Int? = null): String {
        val base = formatDateAsDayMonth(date)
        val cleanExt = extension.lowercase(Locale.US).removePrefix(".")
        return if (suffixNumber != null && suffixNumber > 1) {
            "$base ($suffixNumber).$cleanExt"
        } else {
            "$base.$cleanExt"
        }
    }

    fun loadAssetBitmap(context: Context, assetName: String): Bitmap? {
        return try {
            context.assets.open(assetName).use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun inspectPhoto(context: Context, uri: Uri): PhotoMetadataResult =
        withContext(Dispatchers.IO) {
            val contentResolver = context.contentResolver
            val rawName = queryDisplayName(context, uri) ?: "selected_photo"
            val mimeType = contentResolver.getType(uri) ?: guessMimeTypeFromName(rawName) ?: "image/jpeg"
            val extension = resolveExtension(rawName, mimeType)

            var detectedDate: LocalDate? = null
            var dateSource = "No EXIF capture date found"

            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val exif = ExifInterface(inputStream)

                    val dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    val dateTimeDigitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    val dateTimeGeneral = exif.getAttribute(ExifInterface.TAG_DATETIME)

                    if (!dateTimeOriginal.isNullOrBlank()) {
                        detectedDate = parseExifDateString(dateTimeOriginal)
                        if (detectedDate != null) {
                            dateSource = "EXIF (DateTimeOriginal)"
                        }
                    }

                    if (detectedDate == null && !dateTimeDigitized.isNullOrBlank()) {
                        detectedDate = parseExifDateString(dateTimeDigitized)
                        if (detectedDate != null) {
                            dateSource = "EXIF (DateTimeDigitized)"
                        }
                    }

                    if (detectedDate == null && !dateTimeGeneral.isNullOrBlank()) {
                        detectedDate = parseExifDateString(dateTimeGeneral)
                        if (detectedDate != null) {
                            dateSource = "EXIF (DateTime)"
                        }
                    }
                }
            } catch (e: Exception) {
                dateSource = "Metadata unreadable (${e.localizedMessage ?: "Error"})"
            }

            val previewBitmap = decodeSampledBitmap(context, uri, maxDimension = 800)

            PhotoMetadataResult(
                originalFileName = rawName,
                extension = extension,
                mimeType = mimeType,
                captureDate = detectedDate,
                dateSource = dateSource,
                previewBitmap = previewBitmap
            )
        }

    private fun parseExifDateString(rawExif: String): LocalDate? {
        val trimmed = rawExif.trim()
        if (trimmed.length < 10) return null
        val datePart = trimmed.substring(0, 10)
        for (formatter in exifDatePatterns) {
            try {
                return LocalDate.parse(datePart, formatter)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun guessMimeTypeFromName(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun resolveExtension(fileName: String, mimeType: String): String {
        val fromName = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (fromName in listOf("jpg", "jpeg", "png", "webp", "heic", "heif")) {
            return fromName
        }
        val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.lowercase(Locale.US)
        return fromMime ?: "jpg"
    }

    private fun decodeSampledBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxDimension ||
                options.outHeight / sampleSize > maxDimension
            ) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun doesFileExistInTargetFolder(context: Context, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetFile = File(File(picturesDir, SUBFOLDER_NAME), fileName)
            if (targetFile.exists()) return@withContext true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = "${Environment.DIRECTORY_PICTURES}/$SUBFOLDER_NAME/"
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection =
                    "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf(fileName, relativePath)

                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.count > 0) return@withContext true
                }
            }
            false
        }

    suspend fun findNextAvailableFileName(
        context: Context,
        date: LocalDate,
        extension: String,
        reservedNames: Set<String> = emptySet()
    ): String = withContext(Dispatchers.IO) {
        val firstCandidate = buildFileName(date, extension, suffixNumber = null)
        if (firstCandidate !in reservedNames && !doesFileExistInTargetFolder(context, firstCandidate)) {
            return@withContext firstCandidate
        }
        var suffix = 2
        while (suffix < 1000) {
            val candidate = buildFileName(date, extension, suffixNumber = suffix)
            if (candidate !in reservedNames && !doesFileExistInTargetFolder(context, candidate)) {
                return@withContext candidate
            }
            suffix++
        }
        buildFileName(date, extension, suffixNumber = System.currentTimeMillis().toInt() % 10000)
    }

    suspend fun savePhotoToPictures(
        context: Context,
        sourceUri: Uri,
        fileName: String,
        mimeType: String
    ): Result<SaveResult> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = "${Environment.DIRECTORY_PICTURES}/$SUBFOLDER_NAME"
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val targetUri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: return@withContext Result.failure(IOException("Could not create MediaStore entry."))

                try {
                    resolver.openInputStream(sourceUri)?.use { input ->
                        resolver.openOutputStream(targetUri, "w")?.use { output ->
                            input.copyTo(output)
                        } ?: throw IOException("Could not open output stream.")
                    } ?: throw IOException("Could not read selected image.")

                    val completeValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(targetUri, completeValues, null, null)

                    Result.success(
                        SaveResult(
                            savedUri = targetUri,
                            finalFileName = fileName,
                            displayPath = "Pictures/$SUBFOLDER_NAME/$fileName"
                        )
                    )
                } catch (e: Exception) {
                    resolver.delete(targetUri, null, null)
                    Result.failure(e)
                }
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appFolder = File(picturesDir, SUBFOLDER_NAME)
                if (!appFolder.exists() && !appFolder.mkdirs()) {
                    return@withContext Result.failure(IOException("Failed to create folder: ${appFolder.absolutePath}"))
                }

                val outFile = File(appFolder, fileName)
                resolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext Result.failure(IOException("Could not read selected image."))

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, outFile.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                }
                val insertedUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: Uri.fromFile(outFile)

                Result.success(
                    SaveResult(
                        savedUri = insertedUri,
                        finalFileName = fileName,
                        displayPath = "Pictures/$SUBFOLDER_NAME/$fileName"
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoRenamerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Tải ảnh logo trường và logo CLB từ assets (nếu đã upload)
    val schoolLogoBitmap = remember { PhotoUtils.loadAssetBitmap(context, "school_logo.png") }
    val clubLogoBitmap = remember { PhotoUtils.loadAssetBitmap(context, "club_icon.jpg") }

    var photoItems by remember { mutableStateOf<List<SelectedPhotoItem>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }

    // Quản lý chọn ngày thủ công (cho 1 ảnh cụ thể hoặc cho tất cả ảnh thiếu EXIF)
    var editingItemIndex by remember { mutableStateOf<Int?>(null) }
    var applyManualDateToAllMissing by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showMissingDateAlert by remember { mutableStateOf(false) }

    // Quản lý cảnh báo trùng tên file
    var pendingDuplicateNames by remember { mutableStateOf<List<String>>(emptyList()) }

    // Kết quả lưu & Hiệu ứng pháo giấy
    var savedResults by remember { mutableStateOf<List<SaveResult>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confettiTrigger by remember { mutableStateOf(0) }

    val displayDateFormatter = remember {
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    }

    // Tính toán tên file dự kiến (d-m) cho từng ảnh trong danh sách
    val proposedNamesMap = remember(photoItems) {
        val counts = mutableMapOf<String, Int>()
        val result = mutableMapOf<Int, String>()
        for (item in photoItems) {
            val d = item.chosenDate
            if (d != null) {
                val baseKey = "${PhotoUtils.formatDateAsDayMonth(d)}.${item.metadata.extension.lowercase(Locale.US)}"
                val currentCount = (counts[baseKey] ?: 0) + 1
                counts[baseKey] = currentCount
                result[item.index] = PhotoUtils.buildFileName(
                    date = d,
                    extension = item.metadata.extension,
                    suffixNumber = if (currentCount > 1) currentCount else null
                )
            }
        }
        result
    }

    // Bộ chọn NHIỀU ảnh cùng lúc của Android
    val multiPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            savedResults = emptyList()
            errorMessage = null
            isProcessing = true

            scope.launch {
                val loaded = uris.mapIndexed { idx, uri ->
                    val meta = PhotoUtils.inspectPhoto(context, uri)
                    SelectedPhotoItem(
                        index = idx,
                        uri = uri,
                        metadata = meta,
                        chosenDate = meta.captureDate,
                        dateSourceLabel = meta.dateSource
                    )
                }
                photoItems = loaded
                isProcessing = false

                if (loaded.any { it.chosenDate == null }) {
                    showMissingDateAlert = true
                }
            }
        }
    }

    // Hàm lưu hàng loạt với chế độ tự động thêm hậu tố (2), (3) khi trùng file
    fun executeBatchSave(autoResolveDuplicates: Boolean) {
        if (photoItems.isEmpty() || photoItems.any { it.chosenDate == null }) return
        isProcessing = true
        errorMessage = null
        savedResults = emptyList()

        scope.launch {
            if (!autoResolveDuplicates) {
                val existingConflicts = mutableListOf<String>()
                for (item in photoItems) {
                    val proposed = proposedNamesMap[item.index] ?: continue
                    if (PhotoUtils.doesFileExistInTargetFolder(context, proposed)) {
                        existingConflicts.add(proposed)
                    }
                }
                if (existingConflicts.isNotEmpty()) {
                    isProcessing = false
                    pendingDuplicateNames = existingConflicts
                    return@launch
                }
            }

            val reserved = mutableSetOf<String>()
            val results = mutableListOf<SaveResult>()
            var firstError: String? = null

            for (item in photoItems) {
                val date = item.chosenDate ?: continue
                val finalName = if (autoResolveDuplicates) {
                    PhotoUtils.findNextAvailableFileName(
                        context = context,
                        date = date,
                        extension = item.metadata.extension,
                        reservedNames = reserved
                    )
                } else {
                    proposedNamesMap[item.index] ?: PhotoUtils.buildFileName(date, item.metadata.extension)
                }
                reserved.add(finalName)

                val res = PhotoUtils.savePhotoToPictures(
                    context = context,
                    sourceUri = item.uri,
                    fileName = finalName,
                    mimeType = item.metadata.mimeType
                )
                res.onSuccess {
                    results.add(it)
                }.onFailure { err ->
                    if (firstError == null) {
                        firstError = err.localizedMessage ?: "Unknown save error"
                    }
                }
            }

            isProcessing = false
            if (results.isNotEmpty()) {
                savedResults = results
                confettiTrigger++ // Bắn pháo giấy ăn mừng!
            }
            if (firstError != null) {
                errorMessage = "Some files could not be saved: $firstError"
            }
        }
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            executeBatchSave(autoResolveDuplicates = false)
        } else {
            errorMessage = "Storage permission is required on Android 9 and below."
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = ClubCreamBg,
            topBar = {
                Surface(
                    color = ClubBlack,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Góc trái: Biểu tượng CLB Bóng Chuyền NQD
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            ClubDiamondBadge(bitmap = clubLogoBitmap)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "NQD Volley Attendance",
                                    color = ClubOrangeRed,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "NQD Volleyball Club • Since 2017",
                                    color = Color(0xFFD0C8C5),
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Góc phải: Logo Trường THPT Chuyên Nguyễn Quang Diêu
                        SchoolCornerBadge(
                            bitmap = schoolLogoBitmap,
                            onClick = {
                                Toast.makeText(
                                    context,
                                    "Trường THPT Chuyên Nguyễn Quang Diêu - Đồng Tháp",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Thẻ giới thiệu mang phong cách CLB Bóng Chuyền NQD
                Card(
                    colors = CardDefaults.cardColors(containerColor = ClubDarkCard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, ClubOrangeRed, RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .background(ClubOrangeRed, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "P.E. & CLUB UTILITY",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "THPT Chuyên Nguyễn Quang Diêu",
                                color = Color(0xFFBDB5B2),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Select one or multiple volleyball attendance photos. The app automatically extracts the original EXIF capture date and renames each photo to the required d-m format (e.g., 5-3.jpg).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFF5F0EE)
                        )
                    }
                }

                // Nút chọn 1 hoặc nhiều ảnh
                Button(
                    onClick = {
                        multiPhotoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClubOrangeRed,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text(
                        text = if (photoItems.isEmpty()) "Select Photo(s)" else "Select Different Photo(s)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                if (isProcessing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = ClubOrangeRed
                    )
                }

                // Danh sách các ảnh đã chọn
                if (photoItems.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Selected Photos (${photoItems.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = ClubBlack
                        )
                        if (photoItems.size > 1) {
                            TextButton(
                                onClick = {
                                    applyManualDateToAllMissing = false
                                    editingItemIndex = -1 // -1 nghĩa là đặt ngày cho toàn bộ ảnh đã chọn
                                    showDatePicker = true
                                }
                            ) {
                                Text("Set Date for All", color = ClubOrangeRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    photoItems.forEach { item ->
                        val proposedName = proposedNamesMap[item.index]
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ClubSurface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFFDAD0CC), RoundedCornerShape(12.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Ảnh thu nhỏ (Preview)
                                    Box(
                                        modifier = Modifier
                                            .size(88.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF1E1E1E))
                                            .border(1.dp, ClubBlack, RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (item.metadata.previewBitmap != null) {
                                            Image(
                                                bitmap = item.metadata.previewBitmap.asImageBitmap(),
                                                contentDescription = "Photo preview",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Text(
                                                text = "No Preview",
                                                color = Color.White,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Original: ${item.metadata.originalFileName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.DarkGray,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        if (item.chosenDate != null) {
                                            Text(
                                                text = "Capture Date: ${item.chosenDate.format(displayDateFormatter)}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = ClubBlack
                                            )
                                            Text(
                                                text = "Source: ${item.dateSourceLabel}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF555555)
                                            )
                                        } else {
                                            Text(
                                                text = "No EXIF Capture Date",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            Text(
                                                text = "Tap 'Pick Date' to set manually",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            applyManualDateToAllMissing = false
                                            editingItemIndex = item.index
                                            showDatePicker = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = if (item.chosenDate == null) "Pick Date" else "Change",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Hộp hiển thị tên file d-m dự kiến
                                Surface(
                                    color = ClubBlack,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Proposed Filename:",
                                            color = Color(0xFFCCCCCC),
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = proposedName ?: "Select date first",
                                            color = ClubOrangeRed,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 17.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Nút Rename & Save tất cả ảnh đã chọn
                    val allDatesReady = photoItems.all { it.chosenDate != null }
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                executeBatchSave(autoResolveDuplicates = false)
                            }
                        },
                        enabled = allDatesReady && !isProcessing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClubBlack,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .border(
                                width = if (allDatesReady) 2.dp else 0.dp,
                                color = if (allDatesReady) ClubOrangeRed else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                    ) {
                        Text(
                            text = if (photoItems.size == 1) {
                                "Rename & Save Photo"
                            } else {
                                "Rename & Save All (${photoItems.size} Photos)"
                            },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (allDatesReady) ClubOrangeRed else Color.Gray
                        )
                    }
                }

                // Thông báo lỗi nếu có
                if (errorMessage != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Error",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // Thẻ thông báo Thành Công + Mở ảnh / Chia sẻ nhiều ảnh
                if (savedResults.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ClubBlack),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(2.dp, ClubOrangeRed, RoundedCornerShape(12.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Saved ${savedResults.size} Photo(s) Successfully!",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = ClubOrangeRed
                                )
                                TextButton(onClick = { confettiTrigger++ }) {
                                    Text("🎉 Replay", color = Color.White)
                                }
                            }

                            Text(
                                text = "Folder: Pictures/${PhotoUtils.SUBFOLDER_NAME}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCCCCCC)
                            )

                            savedResults.forEach { res ->
                                Surface(
                                    color = ClubDarkCard,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "✔ ${res.finalFileName}",
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                        TextButton(
                                            onClick = {
                                                openSavedImage(context, res.savedUri, "image/*")
                                            }
                                        ) {
                                            Text("Open", color = ClubOrangeRed, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = {
                                    shareSavedImages(
                                        context = context,
                                        uris = savedResults.map { it.savedUri }
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ClubOrangeRed),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (savedResults.size == 1) "Share Renamed Photo" else "Share All ${savedResults.size} Renamed Photos",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Lớp phủ hiệu ứng Pháo giấy (Confetti) khi hoàn tất đổi tên
        if (confettiTrigger > 0) {
            ConfettiExplosionOverlay(triggerKey = confettiTrigger)
        }
    }

    // Hộp thoại báo ảnh thiếu ngày chụp EXIF
    if (showMissingDateAlert) {
        AlertDialog(
            onDismissRequest = { showMissingDateAlert = false },
            title = { Text("Missing EXIF Capture Date") },
            text = {
                Text(
                    "One or more selected photos do not have an embedded EXIF capture date (DateTimeOriginal). " +
                        "Would you like to choose the capture date manually now?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMissingDateAlert = false
                        applyManualDateToAllMissing = true
                        editingItemIndex = null
                        showDatePicker = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClubOrangeRed)
                ) {
                    Text("Choose Date Manually")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMissingDateAlert = false }) {
                    Text("Later")
                }
            }
        )
    }

    // Hộp thoại chọn ngày trên lịch
    if (showDatePicker) {
        val currentTargetDate = editingItemIndex?.let { idx ->
            photoItems.find { it.index == idx }?.chosenDate
        }
        val initialMillis = currentTargetDate
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli()
            ?: System.currentTimeMillis()

        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis
        )

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                Button(
                    onClick = {
                        val selectedMillis = datePickerState.selectedDateMillis
                        if (selectedMillis != null) {
                            val pickedDate = Instant.ofEpochMilli(selectedMillis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()

                            photoItems = photoItems.map { item ->
                                when {
                                    editingItemIndex == -1 -> item.copy(
                                        chosenDate = pickedDate,
                                        dateSourceLabel = "Selected Manually (Batch)"
                                    )
                                    editingItemIndex == item.index -> item.copy(
                                        chosenDate = pickedDate,
                                        dateSourceLabel = "Selected Manually"
                                    )
                                    applyManualDateToAllMissing && item.chosenDate == null -> item.copy(
                                        chosenDate = pickedDate,
                                        dateSourceLabel = "Selected Manually"
                                    )
                                    else -> item
                                }
                            }
                        }
                        showDatePicker = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClubOrangeRed)
                ) {
                    Text("Confirm Date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Hộp thoại xử lý trùng tên file trong thư mục Pictures/Photo Date Renamer
    if (pendingDuplicateNames.isNotEmpty()) {
        val sampleConflict = pendingDuplicateNames.joinToString(", ")
        AlertDialog(
            onDismissRequest = { pendingDuplicateNames = emptyList() },
            title = { Text("Duplicate Filename Detected") },
            text = {
                Text(
                    "File(s) named $sampleConflict already exist in Pictures/${PhotoUtils.SUBFOLDER_NAME}.\n\n" +
                        "Would you like to automatically add numeric suffixes (e.g., 5-3 (2).jpg) so no existing photos are overwritten?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDuplicateNames = emptyList()
                        executeBatchSave(autoResolveDuplicates = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClubOrangeRed)
                ) {
                    Text("Save with Suffix (2), (3)...")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDuplicateNames = emptyList() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Huy hiệu hình thoi NQD Volleyball Club ở góc trái thanh tiêu đề
 */
@Composable
fun ClubDiamondBadge(bitmap: Bitmap?) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ClubCreamBg)
            .border(1.5.dp, ClubOrangeRed, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "NQD Volleyball Club Logo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val diamond = Path().apply {
                    moveTo(w / 2f, h * 0.08f)
                    lineTo(w * 0.92f, h / 2f)
                    lineTo(w / 2f, h * 0.92f)
                    lineTo(w * 0.08f, h / 2f)
                    close()
                }
                drawPath(diamond, color = ClubBlack)
                drawRect(
                    color = ClubOrangeRed,
                    topLeft = Offset(w * 0.22f, h * 0.42f),
                    size = Size(w * 0.56f, h * 0.16f)
                )
            }
        }
    }
}

/**
 * Huy hiệu tròn Trường THPT Chuyên Nguyễn Quang Diêu ở góc phải thanh tiêu đề
 */
@Composable
fun SchoolCornerBadge(bitmap: Bitmap?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(2.dp, SchoolBlue, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Logo Trường THPT Chuyên Nguyễn Quang Diêu",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Fit
            )
        } else {
            // Hình vẽ dự phòng mô phỏng logo tròn xanh dương & trang sách hồng của trường NQD
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(color = SchoolBlue, radius = r)
                drawCircle(color = Color.White, radius = r * 0.72f)
                // Cánh sen / trang sách hồng
                drawArc(
                    color = SchoolPink,
                    startAngle = 195f,
                    sweepAngle = 150f,
                    useCenter = true,
                    topLeft = Offset(r * 0.45f, r * 0.65f),
                    size = Size(r * 1.1f, r * 0.9f)
                )
                // Biểu tượng học sinh vươn lên màu xanh
                drawCircle(
                    color = SchoolBlue,
                    radius = r * 0.16f,
                    center = Offset(r, r * 0.72f)
                )
            }
        }
    }
}

private data class ConfettiPiece(
    val xFraction: Float,
    val startY: Float,
    val speedY: Float,
    val driftX: Float,
    val sizePx: Float,
    val color: Color,
    val initialRotation: Float,
    val rotationSpeed: Float
)

/**
 * Hiệu ứng Pháo giấy (Confetti) ăn mừng khi đổi tên xong
 */
@Composable
fun ConfettiExplosionOverlay(triggerKey: Int) {
    val progress = remember(triggerKey) { Animatable(0f) }
    val palette = remember {
        listOf(
            ClubOrangeRed,
            Color(0xFFFFB300),
            SchoolBlue,
            SchoolPink,
            Color(0xFF00C853),
            Color.White
        )
    }

    val particles = remember(triggerKey) {
        val rng = Random(triggerKey * 997)
        List(95) {
            ConfettiPiece(
                xFraction = rng.nextFloat(),
                startY = -rng.nextFloat() * 600f,
                speedY = 900f + rng.nextFloat() * 1300f,
                driftX = (rng.nextFloat() - 0.5f) * 320f,
                sizePx = 14f + rng.nextFloat() * 16f,
                color = palette[rng.nextInt(palette.size)],
                initialRotation = rng.nextFloat() * 360f,
                rotationSpeed = (rng.nextFloat() - 0.5f) * 720f
            )
        }
    }

    LaunchedEffect(triggerKey) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 3200, easing = LinearEasing)
        )
    }

    if (progress.value < 1f) {
        val t = progress.value
        val alpha = (1f - (t * 0.85f)).coerceIn(0f, 1f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            particles.forEach { p ->
                val curY = p.startY + p.speedY * t * (height / 1400f)
                val wave = sin(t * 10f + p.xFraction * 6f) * 28f
                val curX = (p.xFraction * width) + (p.driftX * t) + wave

                if (curY in -50f..height + 50f) {
                    rotate(
                        degrees = p.initialRotation + p.rotationSpeed * t,
                        pivot = Offset(curX, curY)
                    ) {
                        drawRect(
                            color = p.color.copy(alpha = alpha),
                            topLeft = Offset(curX - p.sizePx / 2f, curY - p.sizePx / 3f),
                            size = Size(p.sizePx, p.sizePx * 0.65f)
                        )
                    }
                }
            }
        }
    }
}

private fun openSavedImage(context: Context, uri: Uri, mimeType: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open Renamed Photo"))
    } catch (_: Exception) {
        Toast.makeText(context, "No gallery app found to open this image.", Toast.LENGTH_SHORT).show()
    }
}

private fun shareSavedImages(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    try {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(Intent.createChooser(intent, "Share Renamed Photo(s)"))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to share image(s).", Toast.LENGTH_SHORT).show()
    }
}