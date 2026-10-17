package com.school.photodaterenamer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    // Standard EXIF datetime format is "yyyy:MM:dd HH:mm:ss"
    // Some cameras use dashes or omit time; we parse the date portion safely.
    private val exifDatePatterns = listOf(
        DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US)
    )

    /**
     * Format date strictly as "d-m" without leading zeros or year.
     * Examples: 5 March -> "5-3", 12 August -> "12-8", 1 December -> "1-12"
     */
    fun formatDateAsDayMonth(date: LocalDate): String {
        return "${date.dayOfMonth}-${date.monthValue}"
    }

    /**
     * Builds the full proposed filename including extension (e.g., "5-3.jpg").
     */
    fun buildFileName(date: LocalDate, extension: String, suffixNumber: Int? = null): String {
        val base = formatDateAsDayMonth(date)
        val cleanExt = extension.lowercase(Locale.US).removePrefix(".")
        return if (suffixNumber != null && suffixNumber > 1) {
            "$base ($suffixNumber).$cleanExt"
        } else {
            "$base.$cleanExt"
        }
    }

    /**
     * Inspects the selected photo URI to extract its original name, extension, preview bitmap,
     * and EXIF capture date (prioritizing DateTimeOriginal, then DateTimeDigitized, then DateTime).
     */
    suspend fun inspectPhoto(context: Context, uri: Uri): PhotoMetadataResult =
        withContext(Dispatchers.IO) {
            val contentResolver = context.contentResolver

            // 1. Determine MIME type and original filename/extension
            val rawName = queryDisplayName(context, uri) ?: "selected_photo"
            val mimeType = contentResolver.getType(uri) ?: guessMimeTypeFromName(rawName) ?: "image/jpeg"
            val extension = resolveExtension(rawName, mimeType)

            // 2. Read EXIF capture date
            var detectedDate: LocalDate? = null
            var dateSource = "No EXIF capture date found"

            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val exif = ExifInterface(inputStream)

                    val dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    val dateTimeDigitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    val dateTimeGeneral = exif.getAttribute(ExifInterface.TAG_DATETIME)

                    when {
                        !dateTimeOriginal.isNullOrBlank() -> {
                            detectedDate = parseExifDateString(dateTimeOriginal)
                            if (detectedDate != null) {
                                dateSource = "EXIF (DateTimeOriginal)"
                            }
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

            // 3. Load a downsampled Bitmap purely for fast UI preview (does NOT affect saved file)
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
                // Try next pattern
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

    /**
     * Checks whether a file with [fileName] already exists inside Pictures/Photo Date Renamer.
     */
    suspend fun doesFileExistInTargetFolder(context: Context, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            // Check direct filesystem path first (works when app created the file or has visibility)
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetFile = File(File(picturesDir, SUBFOLDER_NAME), fileName)
            if (targetFile.exists()) return@withContext true

            // Also query MediaStore on Android 10+ in case another app or previous install created it
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

    /**
     * Finds the next available numeric suffix filename, e.g., "5-3 (2).jpg", "5-3 (3).jpg".
     */
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

    /**
     * Copies the exact bytes of [sourceUri] to Pictures/Photo Date Renamer/[fileName]
     * without altering or re-compressing the image content.
     */
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
                // Legacy Android 8.0 - 9.0 (API 26 - 28)
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

    /**
     * Saves the exact bytes of [sourceUri] to a custom destination [destinationUri] chosen
     * by the user via Storage Access Framework.
     */
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
