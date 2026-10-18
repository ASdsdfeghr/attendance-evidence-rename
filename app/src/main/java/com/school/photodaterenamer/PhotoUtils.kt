package com.school.photodaterenamer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CaptureDateOrigin {
    EXIF_ORIGINAL,
    FILE_FALLBACK,
    MANUAL_OVERRIDE
}

data class PhotoMetadataResult(
    val originalFileName: String,
    val extension: String,
    val mimeType: String,
    val captureDate: LocalDate,
    val origin: CaptureDateOrigin,
    val previewBitmap: Bitmap?
)

data class SaveResult(
    val savedUri: Uri,
    val finalFileName: String,
    val displayPath: String
)

data class SelectedPhotoItem(
    val id: Int,
    val uri: Uri,
    val metadata: PhotoMetadataResult,
    val chosenDate: LocalDate,
    val dateOrigin: CaptureDateOrigin
)

data class ProposedNameInfo(
    val fileName: String,
    val isDuplicate: Boolean
)

object PhotoUtils {

    const val SUBFOLDER_NAME = "Photo Date Renamer"

    private val exifDatePatterns = listOf(
        DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US)
    )

    private val exifWriteFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

    /**
     * Strictly formats the date as "d-m" without leading zeros or year (e.g. 5-3, 12-8).
     */
    fun formatDateAsDayMonth(date: LocalDate): String {
        return "${date.dayOfMonth}-${date.monthValue}"
    }

    /**
     * Builds the filename using underscore suffix for duplicates:
     * Single: "5-3.jpg"
     * Duplicate: "5-3_2.jpg", "5-3_3.jpg"
     */
    fun buildFileName(date: LocalDate, extension: String, suffixNumber: Int? = null): String {
        val base = formatDateAsDayMonth(date)
        val cleanExt = extension.lowercase(Locale.US).removePrefix(".")
        return if (suffixNumber != null && suffixNumber > 1) {
            "${base}_${suffixNumber}.$cleanExt"
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

    /**
     * Reads EXIF capture date first (DateTimeOriginal -> DateTimeDigitized -> DateTime).
     * If EXIF is missing or unreadable, automatically falls back to the file's last-modified date
     * and flags the origin as FILE_FALLBACK so the UI displays the FALLBACK chip.
     */
    suspend fun inspectPhoto(context: Context, uri: Uri): PhotoMetadataResult =
        withContext(Dispatchers.IO) {
            val contentResolver = context.contentResolver
            val rawName = queryDisplayName(context, uri) ?: "IMG_ATTENDANCE.jpg"
            val mimeType = contentResolver.getType(uri) ?: guessMimeTypeFromName(rawName) ?: "image/jpeg"
            val extension = resolveExtension(rawName, mimeType)

            var detectedDate: LocalDate? = null
            var origin = CaptureDateOrigin.EXIF_ORIGINAL

            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val exif = ExifInterface(inputStream)
                    val dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    val dateTimeDigitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    val dateTimeGeneral = exif.getAttribute(ExifInterface.TAG_DATETIME)

                    if (!dateTimeOriginal.isNullOrBlank()) {
                        detectedDate = parseExifDateString(dateTimeOriginal)
                    }
                    if (detectedDate == null && !dateTimeDigitized.isNullOrBlank()) {
                        detectedDate = parseExifDateString(dateTimeDigitized)
                    }
                    if (detectedDate == null && !dateTimeGeneral.isNullOrBlank()) {
                        detectedDate = parseExifDateString(dateTimeGeneral)
                    }
                }
            } catch (_: Exception) {
                detectedDate = null
            }

            if (detectedDate == null) {
                origin = CaptureDateOrigin.FILE_FALLBACK
                detectedDate = queryFileFallbackDate(context, uri) ?: LocalDate.now()
            }

            val previewBitmap = decodeSampledBitmap(context, uri, maxDimension = 512)

            PhotoMetadataResult(
                originalFileName = rawName,
                extension = extension,
                mimeType = mimeType,
                captureDate = detectedDate!!,
                origin = origin,
                previewBitmap = previewBitmap
            )
        }

    private fun queryFileFallbackDate(context: Context, uri: Uri): LocalDate? {
        val resolver = context.contentResolver
        val columnsToTry = arrayOf(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED
        )
        for (col in columnsToTry) {
            try {
                resolver.query(uri, arrayOf(col), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(col)
                        if (idx >= 0 && !cursor.isNull(idx)) {
                            val rawValue = cursor.getLong(idx)
                            if (rawValue > 0L) {
                                val epochMillis = if (rawValue < 10_000_000_000L) rawValue * 1000L else rawValue
                                return Instant.ofEpochMilli(epochMillis)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
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
        buildFileName(date, extension, suffixNumber = (System.currentTimeMillis() % 10000).toInt())
    }

    /**
     * Saves the renamed photo to Pictures/Photo Date Renamer AND updates the saved copy's
     * EXIF + MediaStore timestamps to the current moment so it appears at the very top
     * of the Gallery / Photo Picker as the latest photo taken.
     */
    suspend fun savePhotoToPictures(
        context: Context,
        sourceUri: Uri,
        fileName: String,
        mimeType: String
    ): Result<SaveResult> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val nowMillis = System.currentTimeMillis()
            val nowSeconds = nowMillis / 1000L
            val nowExifString = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(nowMillis),
                ZoneId.systemDefault()
            ).format(exifWriteFormatter)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = "${Environment.DIRECTORY_PICTURES}/$SUBFOLDER_NAME"
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.DATE_TAKEN, nowMillis)
                    put(MediaStore.Images.Media.DATE_ADDED, nowSeconds)
                    put(MediaStore.Images.Media.DATE_MODIFIED, nowSeconds)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val targetUri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: return@withContext Result.failure(IOException("Could not create MediaStore entry."))

                try {
                    // 1. Copy the raw image bytes losslessly
                    resolver.openInputStream(sourceUri)?.use { input ->
                        resolver.openOutputStream(targetUri, "w")?.use { output ->
                            input.copyTo(output)
                        } ?: throw IOException("Could not open output stream.")
                    } ?: throw IOException("Could not read selected image.")

                    // 2. Update the NEW copy's EXIF header timestamps to NOW before MediaStore scans it.
                    // (ExifInterface modifies only the header block without re-compressing image pixels)
                    try {
                        resolver.openFileDescriptor(targetUri, "rw")?.use { pfd ->
                            val exif = ExifInterface(pfd.fileDescriptor)
                            exif.setAttribute(ExifInterface.TAG_DATETIME, nowExifString)
                            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, nowExifString)
                            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, nowExifString)
                            exif.saveAttributes()
                        }
                    } catch (_: Exception) {
                        // Ignore if image format (e.g. certain PNGs) does not support EXIF writing
                    }

                    // 3. Finalize MediaStore entry with current timestamps so Gallery puts it #1 at the top
                    val completeValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DATE_TAKEN, nowMillis)
                        put(MediaStore.Images.Media.DATE_ADDED, nowSeconds)
                        put(MediaStore.Images.Media.DATE_MODIFIED, nowSeconds)
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
                // Legacy Android 8.0 - 9.0 (API 26 - 28)
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appFolder = File(picturesDir, SUBFOLDER_NAME)
                if (!appFolder.exists() && !appFolder.mkdirs()) {
                    return@withContext Result.failure(IOException("Failed to create target folder."))
                }

                val outFile = File(appFolder, fileName)
                resolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext Result.failure(IOException("Could not read selected image."))

                try {
                    val exif = ExifInterface(outFile)
                    exif.setAttribute(ExifInterface.TAG_DATETIME, nowExifString)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, nowExifString)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, nowExifString)
                    exif.saveAttributes()
                } catch (_: Exception) {
                }
                outFile.setLastModified(nowMillis)

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, outFile.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.DATE_TAKEN, nowMillis)
                    put(MediaStore.Images.Media.DATE_ADDED, nowSeconds)
                    put(MediaStore.Images.Media.DATE_MODIFIED, nowSeconds)
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
