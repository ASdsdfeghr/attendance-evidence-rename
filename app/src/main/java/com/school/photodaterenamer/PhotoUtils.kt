package com.school.photodaterenamer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
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

data class SmartGalleryCandidate(
    val uri: Uri,
    val displayName: String,
    val dateTaken: LocalDate,
    val thumbnail: Bitmap,
    val matchScore: Int,
    val isAttendanceMatch: Boolean,
    val featureBadge: String
)

object PhotoUtils {

    const val SUBFOLDER_NAME = "Photo Date Renamer"

    private val exifDatePatterns = listOf(
        DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US)
    )

    private val exifWriteFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)
    private val alreadyRenamedRegex = Regex("^\\d{1,2}-\\d{1,2}(_\\d+)?\\.[a-zA-Z0-9]+$")

    fun formatDateAsDayMonth(date: LocalDate): String {
        return "${date.dayOfMonth}-${date.monthValue}"
    }

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
     * Scans the user's recent Camera Roll (up to [maxPhotosToScan] images) and evaluates
     * each thumbnail against the 4-part NQD P.E. Attendance visual signature:
     * 1. Bottom Zone: Light ice-blue P.E. shirt + Royal blue collar/stripes
     * 2. Upper Zone: Outdoor campus green foliage + bright sky
     * 3. Mid Left & Right Zones: Two-person close-up selfie framing (skin-tone + dark hair on both halves)
     */
    suspend fun scanDeviceForAttendancePhotos(
        context: Context,
        maxPhotosToScan: Int = 140
    ): List<SmartGalleryCandidate> = withContext(Dispatchers.Default) {
        val resolver = context.contentResolver
        val candidates = mutableListOf<SmartGalleryCandidate>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                var scanned = 0
                while (cursor.moveToNext() && scanned < maxPhotosToScan) {
                    val name = cursor.getString(nameCol) ?: continue
                    val bucket = if (bucketCol >= 0) cursor.getString(bucketCol) ?: "" else ""

                    // Skip screenshots and files already renamed by this app
                    if (name.contains("Screenshot", ignoreCase = true) ||
                        bucket.contains("Screenshot", ignoreCase = true) ||
                        bucket.equals(SUBFOLDER_NAME, ignoreCase = true) ||
                        alreadyRenamedRegex.matches(name)
                    ) {
                        continue
                    }

                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    val rawTaken = cursor.getLong(dateTakenCol)
                    val rawAdded = cursor.getLong(dateAddedCol)
                    val epochMillis = when {
                        rawTaken > 0L -> rawTaken
                        rawAdded > 0L -> rawAdded * 1000L
                        else -> System.currentTimeMillis()
                    }
                    val dateTaken = Instant.ofEpochMilli(epochMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()

                    val thumb = loadFastThumbnail(context, contentUri, id) ?: continue
                    scanned++

                    val analysis = analyzeAttendanceSignature(thumb)
                    candidates.add(
                        SmartGalleryCandidate(
                            uri = contentUri,
                            displayName = name,
                            dateTaken = dateTaken,
                            thumbnail = thumb,
                            matchScore = analysis.score,
                            isAttendanceMatch = analysis.score >= 52,
                            featureBadge = analysis.badge
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        candidates
    }

    private fun loadFastThumbnail(context: Context, uri: Uri, mediaId: Long): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(160, 160), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver,
                    mediaId,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null
                )
            }
        } catch (_: Exception) {
            decodeSampledBitmap(context, uri, maxDimension = 160)
        }
    }

    private data class SignatureAnalysis(val score: Int, val badge: String)

    /**
     * Evaluates a 64x64 grid of the image for the NQD P.E. Attendance visual profile
     * without using face recognition.
     */
    private fun analyzeAttendanceSignature(bitmap: Bitmap): SignatureAnalysis {
        val sampleW = 64
        val sampleH = 64
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, false)
        val pixels = IntArray(sampleW * sampleH)
        scaled.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)

        var bottomTotal = 0
        var iceBlueShirtPixels = 0
        var royalBlueCollarPixels = 0

        var upperTotal = 0
        var foliageGreenPixels = 0
        var brightSkyPixels = 0

        var leftMidTotal = 0
        var rightMidTotal = 0
        var leftSkinPixels = 0
        var rightSkinPixels = 0
        var leftDarkHairPixels = 0
        var rightDarkHairPixels = 0

        val hsv = FloatArray(3)

        for (y in 0 until sampleH) {
            val yNorm = y.toFloat() / sampleH
            for (x in 0 until sampleW) {
                val xNorm = x.toFloat() / sampleW
                val color = pixels[y * sampleW + x]
                AndroidColor.colorToHSV(color, hsv)
                val h = hsv[0] // 0..360
                val s = hsv[1] // 0..1
                val v = hsv[2] // 0..1

                // 1. Bottom 42% Zone: NQD Light-Blue Uniform + Royal-Blue Collar/Stripes
                if (yNorm >= 0.58f) {
                    bottomTotal++
                    // Royal/Cobalt blue collar & shoulder stripes
                    if (h in 205f..252f && s in 0.48f..1.0f && v in 0.20f..0.88f) {
                        royalBlueCollarPixels++
                    }
                    // Ice-blue / pale cool-white P.E. shirt body
                    if ((h in 182f..242f && s in 0.06f..0.46f && v in 0.52f..0.99f) ||
                        (s < 0.10f && v in 0.72f..0.98f)
                    ) {
                        iceBlueShirtPixels++
                    }
                }

                // 2. Upper 65% Zone: Campus Trees + Daylight Sky
                if (yNorm <= 0.65f) {
                    upperTotal++
                    // Green foliage
                    if (h in 58f..162f && s in 0.16f..0.92f && v in 0.14f..0.82f) {
                        foliageGreenPixels++
                    }
                    // Bright sky or sky-blue clouds in top 48%
                    if (yNorm <= 0.48f) {
                        if ((v >= 0.76f && s <= 0.28f) ||
                            (h in 188f..232f && s in 0.12f..0.65f && v >= 0.62f)
                        ) {
                            brightSkyPixels++
                        }
                    }
                }

                // 3. Mid Zone (Y: 15%..78%): Dual-Subject Left & Right Presence (Skin + Hair)
                if (yNorm in 0.15f..0.78f) {
                    val isSkin = h in 6f..44f && s in 0.15f..0.68f && v in 0.32f..0.95f
                    val isDarkHair = v < 0.23f

                    if (xNorm < 0.5f) {
                        leftMidTotal++
                        if (isSkin) leftSkinPixels++
                        if (isDarkHair) leftDarkHairPixels++
                    } else {
                        rightMidTotal++
                        if (isSkin) rightSkinPixels++
                        if (isDarkHair) rightDarkHairPixels++
                    }
                }
            }
        }

        val shirtRatio = iceBlueShirtPixels.toFloat() / bottomTotal.coerceAtLeast(1)
        val collarRatio = royalBlueCollarPixels.toFloat() / bottomTotal.coerceAtLeast(1)
        val greenRatio = foliageGreenPixels.toFloat() / upperTotal.coerceAtLeast(1)
        val skyRatio = brightSkyPixels.toFloat() / upperTotal.coerceAtLeast(1)

        val leftSkinRatio = leftSkinPixels.toFloat() / leftMidTotal.coerceAtLeast(1)
        val rightSkinRatio = rightSkinPixels.toFloat() / rightMidTotal.coerceAtLeast(1)
        val leftHairRatio = leftDarkHairPixels.toFloat() / leftMidTotal.coerceAtLeast(1)
        val rightHairRatio = rightDarkHairPixels.toFloat() / rightMidTotal.coerceAtLeast(1)

        // Score Component A: NQD P.E. Uniform (max 42 pts)
        var uniformPts = 0
        if (shirtRatio >= 0.16f) uniformPts += 20 else if (shirtRatio >= 0.08f) uniformPts += 10
        if (collarRatio >= 0.018f) uniformPts += 22 else if (collarRatio >= 0.008f) uniformPts += 12

        // Score Component B: Outdoor Campus Trees + Sky (max 28 pts)
        var outdoorPts = 0
        if (greenRatio >= 0.08f) outdoorPts += 15 else if (greenRatio >= 0.03f) outdoorPts += 8
        if (skyRatio >= 0.08f) outdoorPts += 13 else if (skyRatio >= 0.03f) outdoorPts += 7

        // Score Component C: Two-Person Left/Right Selfie Balance (max 30 pts)
        var duoPts = 0
        val bothSidesHaveSkin = leftSkinRatio >= 0.05f && rightSkinRatio >= 0.05f
        val bothSidesHaveHair = leftHairRatio >= 0.04f && rightHairRatio >= 0.04f
        if (bothSidesHaveSkin) duoPts += 20 else if (leftSkinRatio + rightSkinRatio >= 0.10f) duoPts += 8
        if (bothSidesHaveHair) duoPts += 10

        val totalScore = (uniformPts + outdoorPts + duoPts).coerceIn(0, 99)

        val tags = mutableListOf<String>()
        if (uniformPts >= 22) tags.add("NQD SHIRT")
        if (duoPts >= 20) tags.add("2+ PPL")
        if (outdoorPts >= 15) tags.add("CAMPUS")
        val badgeText = if (tags.isNotEmpty()) tags.take(2).joinToString(" • ") else "CAMERA"

        return SignatureAnalysis(score = totalScore, badge = badgeText)
    }

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
                    resolver.openInputStream(sourceUri)?.use { input ->
                        resolver.openOutputStream(targetUri, "w")?.use { output ->
                            input.copyTo(output)
                        } ?: throw IOException("Could not open output stream.")
                    } ?: throw IOException("Could not read selected image.")

                    try {
                        resolver.openFileDescriptor(targetUri, "rw")?.use { pfd ->
                            val exif = ExifInterface(pfd.fileDescriptor)
                            exif.setAttribute(ExifInterface.TAG_DATETIME, nowExifString)
                            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, nowExifString)
                            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, nowExifString)
                            exif.saveAttributes()
                        }
                    } catch (_: Exception) {
                    }

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