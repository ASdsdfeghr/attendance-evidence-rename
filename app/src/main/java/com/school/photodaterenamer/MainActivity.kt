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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

private val AppColorScheme = lightColorScheme()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContent {
                MaterialTheme(colorScheme = AppColorScheme) {
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

            val previewBitmap = decodeSampledBitmap(context, uri, maxDimension = 1024)

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
        extension: String
    ): String = withContext(Dispatchers.IO) {
        var suffix = 2
        while (suffix < 1000) {
            val candidate = buildFileName(date, extension, suffixNumber = suffix)
            if (!doesFileExistInTargetFolder(context, candidate)) {
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

    suspend fun savePhotoToCustomUri(
        context: Context,
        sourceUri: Uri,
        destinationUri: Uri,
        fallbackFileName: String
    ): Result<SaveResult> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(destinationUri, "w")?.use { output ->
                    input.copyTo(output)
                } ?: throw IOException("Could not open destination output stream.")
            } ?: throw IOException("Could not read source image.")

            val actualName = queryDisplayName(context, destinationUri) ?: fallbackFileName
            Result.success(
                SaveResult(
                    savedUri = destinationUri,
                    finalFileName = actualName,
                    displayPath = "Custom location ($actualName)"
                )
            )
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

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var metadataResult by remember { mutableStateOf<PhotoMetadataResult?>(null) }
    var chosenDate by remember { mutableStateOf<LocalDate?>(null) }
    var dateSourceLabel by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showMissingDateAlert by remember { mutableStateOf(false) }
    var duplicateCandidateSuffixName by remember { mutableStateOf<String?>(null) }

    var saveResult by remember { mutableStateOf<SaveResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val displayDateFormatter = remember {
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    }

    val proposedFileName = remember(chosenDate, metadataResult) {
        val date = chosenDate
        val meta = metadataResult
        if (date != null && meta != null) {
            PhotoUtils.buildFileName(date, meta.extension)
        } else {
            null
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            saveResult = null
            errorMessage = null
            isProcessing = true

            scope.launch {
                val result = PhotoUtils.inspectPhoto(context, uri)
                metadataResult = result
                chosenDate = result.captureDate
                dateSourceLabel = result.dateSource
                isProcessing = false

                if (result.captureDate == null) {
                    showMissingDateAlert = true
                }
            }
        }
    }

    val customDestinationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(metadataResult?.mimeType ?: "image/jpeg")
    ) { destUri: Uri? ->
        val srcUri = selectedUri
        val fName = proposedFileName
        if (destUri != null && srcUri != null && fName != null) {
            isProcessing = true
            scope.launch {
                val res = PhotoUtils.savePhotoToCustomUri(context, srcUri, destUri, fName)
                isProcessing = false
                res.onSuccess {
                    saveResult = it
                    errorMessage = null
                }.onFailure { err ->
                    errorMessage = "Failed to save image: ${err.localizedMessage ?: "Unknown error"}"
                }
            }
        }
    }

    fun initiateSave() {
        val uri = selectedUri ?: return
        val meta = metadataResult ?: return
        val date = chosenDate ?: return
        val targetName = PhotoUtils.buildFileName(date, meta.extension)

        isProcessing = true
        errorMessage = null
        saveResult = null

        scope.launch {
            val exists = PhotoUtils.doesFileExistInTargetFolder(context, targetName)
            if (exists) {
                val nextAvailable = PhotoUtils.findNextAvailableFileName(context, date, meta.extension)
                isProcessing = false
                duplicateCandidateSuffixName = nextAvailable
            } else {
                val res = PhotoUtils.savePhotoToPictures(context, uri, targetName, meta.mimeType)
                isProcessing = false
                res.onSuccess {
                    saveResult = it
                }.onFailure { err ->
                    errorMessage = "Could not save file: ${err.localizedMessage ?: "Unknown error"}"
                }
            }
        }
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            initiateSave()
        } else {
            errorMessage = "Storage permission is required on Android 9 and below to save to Pictures."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Photo Date Renamer",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
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
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "P.E. Attendance Photo Utility",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Select an attendance photo to automatically read the date it was taken from its EXIF metadata and save a copy renamed in the required d-m format (e.g., 5-3.jpg).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Button(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(text = "Select Photo", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            if (isProcessing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (selectedUri != null && metadataResult != null) {
                val meta = metadataResult!!

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Selected Photo Preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFEEEEEE))
                                .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (meta.previewBitmap != null) {
                                Image(
                                    bitmap = meta.previewBitmap.asImageBitmap(),
                                    contentDescription = "Selected photo preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Text(
                                    text = "Preview unavailable (Image format unreadable)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.DarkGray
                                )
                            }
                        }

                        Text(
                            text = "Original file: ${meta.originalFileName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Detected Capture Date",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                if (chosenDate != null) {
                                    Text(
                                        text = chosenDate!!.format(displayDateFormatter),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Source: $dateSourceLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = "Not available in metadata",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Please choose the capture date manually.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            OutlinedButton(onClick = { showDatePicker = true }) {
                                Text(if (chosenDate == null) "Pick Date" else "Change Date")
                            }
                        }

                        HorizontalDivider()

                        Column {
                            Text(
                                text = "Proposed Filename (d-m format)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = proposedFileName ?: "Select a date first",
                                    modifier = Modifier.padding(12.dp),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

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
                                    initiateSave()
                                }
                            },
                            enabled = chosenDate != null && !isProcessing && meta.previewBitmap != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = "Rename & Save",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

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

            if (saveResult != null) {
                val result = saveResult!!
                val mime = metadataResult?.mimeType ?: "image/jpeg"

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE8F5E9)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF4CAF50), RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Photo Renamed & Saved Successfully!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B5E20)
                        )
                        Text(
                            text = "New Filename: ${result.finalFileName}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1B5E20)
                        )
                        Text(
                            text = "Location: ${result.displayPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { openSavedImage(context, result.savedUri, mime) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Open Image")
                            }
                            Button(
                                onClick = { shareSavedImage(context, result.savedUri, mime) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Share Image")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMissingDateAlert) {
        AlertDialog(
            onDismissRequest = { showMissingDateAlert = false },
            title = { Text("No Capture Date Found") },
            text = {
                Text(
                    "This image does not contain a valid EXIF capture date (DateTimeOriginal or DateTimeDigitized). " +
                        "Please select the date the photo was taken manually."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMissingDateAlert = false
                        showDatePicker = true
                    }
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

    if (showDatePicker) {
        val initialMillis = chosenDate
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
                            chosenDate = Instant.ofEpochMilli(selectedMillis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            dateSourceLabel = "Selected Manually"
                        }
                        showDatePicker = false
                    }
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

    if (duplicateCandidateSuffixName != null && proposedFileName != null) {
        val suffixName = duplicateCandidateSuffixName!!
        val originalTarget = proposedFileName!!

        AlertDialog(
            onDismissRequest = { duplicateCandidateSuffixName = null },
            title = { Text("Duplicate Filename Detected") },
            text = {
                Text(
                    "A file named \"$originalTarget\" already exists in Pictures/${PhotoUtils.SUBFOLDER_NAME}.\n\n" +
                        "To avoid overwriting your existing photo, how would you like to save this file?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        duplicateCandidateSuffixName = null
                        val uri = selectedUri ?: return@Button
                        val meta = metadataResult ?: return@Button
                        isProcessing = true
                        scope.launch {
                            val res = PhotoUtils.savePhotoToPictures(
                                context = context,
                                sourceUri = uri,
                                fileName = suffixName,
                                mimeType = meta.mimeType
                            )
                            isProcessing = false
                            res.onSuccess {
                                saveResult = it
                            }.onFailure { err ->
                                errorMessage = "Failed to save: ${err.localizedMessage}"
                            }
                        }
                    }
                ) {
                    Text("Save as $suffixName")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            duplicateCandidateSuffixName = null
                            customDestinationLauncher.launch(originalTarget)
                        }
                    ) {
                        Text("Choose Folder")
                    }
                    TextButton(
                        onClick = { duplicateCandidateSuffixName = null }
                    ) {
                        Text("Cancel")
                    }
                }
            }
        )
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

private fun shareSavedImage(context: Context, uri: Uri, mimeType: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Renamed Photo"))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to share image.", Toast.LENGTH_SHORT).show()
    }
}