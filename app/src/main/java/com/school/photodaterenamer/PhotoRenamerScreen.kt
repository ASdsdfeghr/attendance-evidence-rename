package com.school.photodaterenamer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sin
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoRenamerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val schoolLogoBitmap = remember { PhotoUtils.loadAssetBitmap(context, "school_logo.png") }
    val clubLogoBitmap = remember { PhotoUtils.loadAssetBitmap(context, "club_icon.jpg") }

    var photoItems by remember { mutableStateOf<List<SelectedPhotoItem>>(emptyList()) }
    var savedResults by remember { mutableStateOf<List<SaveResult>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var confettiTrigger by remember { mutableIntStateOf(0) }

    // Custom Smart Selector state
    var showSmartPickerSheet by remember { mutableStateOf(false) }
    var isScanningGallery by remember { mutableStateOf(false) }
    var smartCandidates by remember { mutableStateOf<List<SmartGalleryCandidate>>(emptyList()) }

    // Manual date override dialog state
    var editingItemId by remember { mutableStateOf<Int?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    val proposedNamesMap = remember(photoItems) {
        val dateCounts = mutableMapOf<LocalDate, Int>()
        photoItems.forEach { item ->
            dateCounts[item.chosenDate] = (dateCounts[item.chosenDate] ?: 0) + 1
        }

        val runningIndexByBase = mutableMapOf<String, Int>()
        val map = mutableMapOf<Int, ProposedNameInfo>()

        for (item in photoItems) {
            val baseKey = "${PhotoUtils.formatDateAsDayMonth(item.chosenDate)}.${item.metadata.extension.lowercase(Locale.US)}"
            val occurrence = (runningIndexByBase[baseKey] ?: 0) + 1
            runningIndexByBase[baseKey] = occurrence

            val isSameDayCollision = (dateCounts[item.chosenDate] ?: 0) > 1
            val generatedName = PhotoUtils.buildFileName(
                date = item.chosenDate,
                extension = item.metadata.extension,
                suffixNumber = if (occurrence > 1) occurrence else null
            )

            map[item.id] = ProposedNameInfo(
                fileName = generatedName,
                isDuplicate = isSameDayCollision
            )
        }
        map
    }

    fun loadSelectedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        savedResults = emptyList()
        isProcessing = true
        scope.launch {
            try {
                val loaded = uris.mapIndexed { idx, uri ->
                    val meta = PhotoUtils.inspectPhoto(context, uri)
                    SelectedPhotoItem(
                        id = idx,
                        uri = uri,
                        metadata = meta,
                        chosenDate = meta.captureDate,
                        dateOrigin = meta.origin
                    )
                }
                photoItems = loaded
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.error_reading_photos, e.localizedMessage ?: "")
                )
            } finally {
                isProcessing = false
            }
        }
    }

    // Fallback Native Android Photo Picker
    val systemPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        loadSelectedUris(uris)
    }

    fun openSmartSelectorAndScan() {
        showSmartPickerSheet = true
        isScanningGallery = true
        scope.launch {
            smartCandidates = PhotoUtils.scanDeviceForAttendancePhotos(context)
            isScanningGallery = false
        }
    }

    val readMediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openSmartSelectorAndScan()
        } else {
            // Gracefully fall back to system photo picker if gallery permission is declined
            systemPhotoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    fun handleSelectPhotosButtonClick() {
        val readPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(context, readPerm) == PackageManager.PERMISSION_GRANTED) {
            openSmartSelectorAndScan()
        } else {
            readMediaPermissionLauncher.launch(readPerm)
        }
    }

    fun runBatchRename() {
        if (photoItems.isEmpty() || isProcessing) return
        isProcessing = true
        savedResults = emptyList()

        scope.launch {
            val reservedNames = mutableSetOf<String>()
            val successes = mutableListOf<SaveResult>()
            var firstError: String? = null

            for (item in photoItems) {
                val targetName = PhotoUtils.findNextAvailableFileName(
                    context = context,
                    date = item.chosenDate,
                    extension = item.metadata.extension,
                    reservedNames = reservedNames
                )
                reservedNames.add(targetName)

                val result = PhotoUtils.savePhotoToPictures(
                    context = context,
                    sourceUri = item.uri,
                    fileName = targetName,
                    mimeType = item.metadata.mimeType
                )
                result.onSuccess {
                    successes.add(it)
                }.onFailure { err ->
                    if (firstError == null) {
                        firstError = err.localizedMessage
                    }
                }
            }

            isProcessing = false
            if (successes.isNotEmpty()) {
                savedResults = successes
                confettiTrigger++
            }
            if (firstError != null) {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.error_saving_photos, firstError ?: "")
                )
            }
        }
    }

    val legacyWritePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            runBatchRename()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.error_storage_permission)
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = PosterInk,
                        contentColor = PosterOffWhite,
                        actionColor = PosterOrangeRed,
                        shape = JerseyCardShape
                    )
                }
            },
            topBar = {
                MatchDayHeader(
                    clubLogoBitmap = clubLogoBitmap,
                    schoolLogoBitmap = schoolLogoBitmap,
                    onSchoolBadgeClick = {
                        Toast.makeText(
                            context,
                            context.getString(R.string.school_subtitle),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            },
            bottomBar = {
                MatchDayBottomBar(
                    photoCount = photoItems.size,
                    isEnabled = photoItems.isNotEmpty() && !isProcessing && savedResults.isEmpty(),
                    isProcessing = isProcessing,
                    onRenameAllClick = {
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            legacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            runBatchRename()
                        }
                    }
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (isProcessing) {
                    item(key = "progress_bar") {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(JerseyChipShape),
                            color = PosterOrangeRed,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }

                // 2. Hero Card
                item(key = "hero_card") {
                    MatchDayHeroCard(
                        onSelectPhotosClick = { handleSelectPhotosButtonClick() }
                    )
                }

                // 6. Result State
                if (savedResults.isNotEmpty()) {
                    item(key = "result_state") {
                        MatchDayResultCard(
                            renamedCount = savedResults.size,
                            savedResults = savedResults,
                            onShareAllClick = {
                                shareSavedImages(context, savedResults.map { it.savedUri })
                            },
                            onStartOverClick = {
                                savedResults = emptyList()
                                photoItems = emptyList()
                            },
                            onOpenSingleClick = { uri ->
                                openSavedImage(context, uri)
                            }
                        )
                    }
                }

                // 5. Empty State
                if (photoItems.isEmpty() && !isProcessing && savedResults.isEmpty()) {
                    item(key = "empty_state") {
                        VolleyballEmptyState()
                    }
                }

                // 3. Selected Photos Roster
                if (photoItems.isNotEmpty()) {
                    item(key = "roster_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.roster_section_title).uppercase(Locale.getDefault()),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = stringResource(R.string.tap_date_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = PosterGray
                            )
                        }
                    }

                    itemsIndexed(
                        items = photoItems,
                        key = { _, item -> "${item.id}_${item.uri}" }
                    ) { index, item ->
                        val nameInfo = proposedNamesMap[item.id] ?: ProposedNameInfo(
                            fileName = PhotoUtils.buildFileName(item.chosenDate, item.metadata.extension),
                            isDuplicate = false
                        )

                        AnimatedRosterRowWrapper(index = index) {
                            JerseyPhotoRow(
                                item = item,
                                proposedFileName = nameInfo.fileName,
                                isDuplicate = nameInfo.isDuplicate,
                                onChangeDateClick = {
                                    editingItemId = item.id
                                    showDatePicker = true
                                }
                            )
                        }
                    }
                }
            }
        }

        if (confettiTrigger > 0) {
            ConfettiExplosionOverlay(triggerKey = confettiTrigger)
        }
    }

    // Custom Smart Attendance Photo Selector Modal Sheet
    if (showSmartPickerSheet) {
        SmartAttendancePickerSheet(
            candidates = smartCandidates,
            isScanning = isScanningGallery,
            onDismiss = { showSmartPickerSheet = false },
            onConfirmSelection = { chosenUris ->
                showSmartPickerSheet = false
                loadSelectedUris(chosenUris)
            },
            onOpenSystemPicker = {
                showSmartPickerSheet = false
                systemPhotoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        )
    }

    // Manual Date Picker Override Dialog
    if (showDatePicker && editingItemId != null) {
        val currentItem = photoItems.find { it.id == editingItemId }
        val initialMillis = currentItem?.chosenDate
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli()
            ?: System.currentTimeMillis()

        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                PosterButton(
                    text = stringResource(R.string.action_confirm_date),
                    onClick = {
                        val selectedMillis = datePickerState.selectedDateMillis
                        if (selectedMillis != null && editingItemId != null) {
                            val newDate = Instant.ofEpochMilli(selectedMillis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            photoItems = photoItems.map { item ->
                                if (item.id == editingItemId) {
                                    item.copy(
                                        chosenDate = newDate,
                                        dateOrigin = CaptureDateOrigin.MANUAL_OVERRIDE
                                    )
                                } else {
                                    item
                                }
                            }
                            savedResults = emptyList()
                        }
                        showDatePicker = false
                    },
                    modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.titleLarge,
                        color = PosterGray
                    )
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/**
 * Custom Match-Day Smart Selector Modal Sheet:
 * Automatically filters the camera roll by the NQD P.E. Uniform + Campus + 2-Person Selfie signature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartAttendancePickerSheet(
    candidates: List<SmartGalleryCandidate>,
    isScanning: Boolean,
    onDismiss: () -> Unit,
    onConfirmSelection: (List<Uri>) -> Unit,
    onOpenSystemPicker: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showOnlyMatches by remember { mutableStateOf(true) }
    val selectedUris = remember { mutableStateListOf<Uri>() }

    val matchedList = remember(candidates) {
        candidates.filter { it.isAttendanceMatch }.sortedByDescending { it.matchScore }
    }

    // If no photos matched the strict threshold, automatically show all camera photos
    LaunchedEffect(isScanning, matchedList.size) {
        if (!isScanning && matchedList.isEmpty() && candidates.isNotEmpty()) {
            showOnlyMatches = false
        }
    }

    val displayedPhotos = if (showOnlyMatches) matchedList else candidates

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PosterInk,
        contentColor = PosterOffWhite,
        shape = JerseyCardShape,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
        ) {
            // Top Poster Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PosterCharcoal)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.smart_picker_title).uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.headlineMedium,
                            color = PosterOffWhite
                        )
                        Text(
                            text = stringResource(R.string.smart_picker_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = PosterOrangeRed
                        )
                    }

                    TextButton(
                        onClick = onOpenSystemPicker,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.smart_picker_system_btn).uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = PosterGray
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                            tint = PosterOffWhite
                        )
                    }
                }

                // Filter Tabs: SMART MATCH vs ALL CAMERA
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterTabChip(
                        label = stringResource(R.string.smart_tab_detected, matchedList.size),
                        selected = showOnlyMatches,
                        onClick = { showOnlyMatches = true },
                        modifier = Modifier.weight(1f)
                    )
                    FilterTabChip(
                        label = stringResource(R.string.smart_tab_all, candidates.size),
                        selected = !showOnlyMatches,
                        onClick = { showOnlyMatches = false },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(PosterOrangeRed)
            )

            // Center Content: Scanning state or 3-Column Jersey Grid
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isScanning) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = PosterOrangeRed)
                        Text(
                            text = stringResource(R.string.smart_picker_scanning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PosterGray
                        )
                    }
                } else if (displayedPhotos.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.smart_picker_empty),
                            style = MaterialTheme.typography.titleMedium,
                            color = PosterGray,
                            textAlign = TextAlign.Center
                        )
                        PosterOutlinedButton(
                            text = stringResource(R.string.smart_picker_system_btn),
                            onClick = onOpenSystemPicker
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = displayedPhotos,
                            key = { it.uri.toString() }
                        ) { candidate ->
                            val isSelected = candidate.uri in selectedUris
                            SmartPhotoGridCell(
                                candidate = candidate,
                                isSelected = isSelected,
                                onToggle = {
                                    if (isSelected) {
                                        selectedUris.remove(candidate.uri)
                                    } else {
                                        selectedUris.add(candidate.uri)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Bottom Sticky Confirm Selection Bar
            Surface(
                color = PosterCharcoal,
                contentColor = PosterOffWhite
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PosterButton(
                        text = stringResource(R.string.smart_picker_add_btn, selectedUris.size),
                        enabled = selectedUris.isNotEmpty(),
                        icon = Icons.Default.Check,
                        onClick = { onConfirmSelection(selectedUris.toList()) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = JerseyChipShape,
        color = if (selected) PosterOrangeRed else PosterInk,
        contentColor = if (selected) PosterOffWhite else PosterGray,
        border = BorderStroke(1.dp, if (selected) PosterOrangeRed else PosterDarkOutline),
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(JerseyChipShape)
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = label.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SmartPhotoGridCell(
    candidate: SmartGalleryCandidate,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val dayMonthTag = remember(candidate.dateTaken) {
        PhotoUtils.formatDateAsDayMonth(candidate.dateTaken)
    }

    Box(
        modifier = Modifier
            .aspectRatio(0.85f)
            .clip(JerseyThumbShape)
            .background(PosterCharcoal)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) PosterOrangeRed else PosterDarkOutline,
                shape = JerseyThumbShape
            )
            .clickable(onClick = onToggle)
    ) {
        Image(
            bitmap = candidate.thumbnail.asImageBitmap(),
            contentDescription = candidate.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Top-Left: Match Score Badge for Detected Attendance Shots
        if (candidate.isAttendanceMatch) {
            Surface(
                shape = JerseyChipShape,
                color = PosterInk.copy(alpha = 0.86f),
                border = BorderStroke(1.dp, PosterOrangeRed),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            ) {
                Text(
                    text = "${candidate.matchScore}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = PosterOrangeRed,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }

        // Top-Right: Selection Checkmark Circle
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isSelected) PosterOrangeRed else PosterInk.copy(alpha = 0.65f))
                .border(1.5.dp, PosterOffWhite, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = PosterOffWhite,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        // Bottom Bar: Preview of d-m filename + Detected Feature Tag
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(PosterInk.copy(alpha = 0.82f))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$dayMonthTag.jpg",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = PosterOffWhite
                )
            }
            Text(
                text = candidate.featureBadge,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = if (candidate.isAttendanceMatch) PosterGreen else PosterGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AnimatedRosterRowWrapper(
    index: Int,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(8) * 45L))
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(260)) +
            slideInVertically(
                initialOffsetY = { it / 3 },
                animationSpec = tween(260)
            )
    ) {
        content()
    }
}

@Composable
fun MatchDayHeader(
    clubLogoBitmap: Bitmap?,
    schoolLogoBitmap: Bitmap?,
    onSchoolBadgeClick: () -> Unit
) {
    Surface(
        color = PosterInk,
        contentColor = PosterOffWhite,
        shadowElevation = 8.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .clipToBounds()
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height

                val stripe1 = Path().apply {
                    moveTo(w * 0.62f, 0f)
                    lineTo(w * 0.72f, 0f)
                    lineTo(w * 0.56f, h)
                    lineTo(w * 0.46f, h)
                    close()
                }
                drawPath(stripe1, color = PosterOrangeRed.copy(alpha = 0.22f))

                val stripe2 = Path().apply {
                    moveTo(w * 0.76f, 0f)
                    lineTo(w * 0.81f, 0f)
                    lineTo(w * 0.65f, h)
                    lineTo(w * 0.60f, h)
                    close()
                }
                drawPath(stripe2, color = PosterOrangeRed.copy(alpha = 0.40f))
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ClubDiamondLogo(bitmap = clubLogoBitmap)

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.header_title).uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontSize = 34.sp,
                                lineHeight = 33.sp
                            ),
                            color = PosterOffWhite,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.school_subtitle).uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.8.sp
                            ),
                            color = PosterOrangeRed,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    SchoolHeaderBadge(
                        bitmap = schoolLogoBitmap,
                        onClick = onSchoolBadgeClick
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(PosterOrangeRed)
                )
            }
        }
    }
}

@Composable
fun MatchDayHeroCard(
    onSelectPhotosClick: () -> Unit
) {
    Surface(
        shape = JerseyCardShape,
        color = PosterCharcoal,
        contentColor = PosterOffWhite,
        border = BorderStroke(1.5.dp, PosterOrangeRed),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .matchParentSize()
                    .background(PosterOrangeRed)
                    .align(Alignment.CenterStart)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.hero_tagline).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.headlineSmall,
                    color = PosterOrangeRed
                )

                Text(
                    text = stringResource(R.string.hero_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PosterOffWhite
                )

                Spacer(modifier = Modifier.height(4.dp))

                PosterButton(
                    text = stringResource(R.string.action_select_photos),
                    icon = Icons.Default.Add,
                    onClick = onSelectPhotosClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun JerseyPhotoRow(
    item: SelectedPhotoItem,
    proposedFileName: String,
    isDuplicate: Boolean,
    onChangeDateClick: () -> Unit
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault()) }
    val changeDateDesc = stringResource(R.string.cd_change_date, item.metadata.originalFileName)

    Surface(
        shape = JerseyCardShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isDuplicate) PosterOrangeRed else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(JerseyCardShape)
            .clickable(
                role = Role.Button,
                onClickLabel = changeDateDesc,
                onClick = onChangeDateClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
        ) {
            Text(
                text = item.chosenDate.dayOfMonth.toString(),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 96.sp,
                    fontStyle = FontStyle.Italic
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(JerseyThumbShape)
                            .background(PosterInk)
                            .border(1.dp, PosterOrangeRed, JerseyThumbShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (item.metadata.previewBitmap != null) {
                            Image(
                                bitmap = item.metadata.previewBitmap.asImageBitmap(),
                                contentDescription = stringResource(
                                    R.string.cd_photo_thumbnail,
                                    item.metadata.originalFileName
                                ),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = "RAW",
                                style = MaterialTheme.typography.labelSmall,
                                color = PosterGray
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = item.metadata.originalFileName,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium
                                ),
                                color = PosterGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )

                            Text(
                                text = "→",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = PosterOrangeRed
                            )

                            Surface(
                                shape = JerseyChipShape,
                                color = PosterOrangeRed,
                                contentColor = PosterOffWhite
                            ) {
                                Text(
                                    text = proposedFileName,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = null,
                                tint = PosterGray,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = item.chosenDate.format(dateFormatter),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (item.dateOrigin == CaptureDateOrigin.FILE_FALLBACK) {
                                StatusBadgeChip(
                                    text = stringResource(R.string.chip_fallback),
                                    backgroundColor = PosterCharcoal,
                                    contentColor = PosterWarningAmber,
                                    borderColor = PosterWarningAmber
                                )
                            } else if (item.dateOrigin == CaptureDateOrigin.MANUAL_OVERRIDE) {
                                StatusBadgeChip(
                                    text = stringResource(R.string.chip_manual),
                                    backgroundColor = PosterCharcoal,
                                    contentColor = PosterOffWhite,
                                    borderColor = PosterGray
                                )
                            }

                            if (isDuplicate) {
                                StatusBadgeChip(
                                    text = stringResource(R.string.chip_duplicate),
                                    backgroundColor = PosterOrangeTint,
                                    contentColor = PosterWarningAmber,
                                    borderColor = PosterOrangeRed,
                                    leadingIcon = Icons.Default.Warning
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadgeChip(
    text: String,
    backgroundColor: Color,
    contentColor: Color,
    borderColor: Color,
    leadingIcon: ImageVector? = null
) {
    Surface(
        shape = JerseyChipShape,
        color = backgroundColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(11.dp)
                )
            }
            Text(
                text = text.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Composable
fun MatchDayBottomBar(
    photoCount: Int,
    isEnabled: Boolean,
    isProcessing: Boolean,
    onRenameAllClick: () -> Unit
) {
    Surface(
        color = PosterInk,
        contentColor = PosterOffWhite,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(PosterCharcoal)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(0.42f)) {
                    Text(
                        text = stringResource(R.string.bottom_bar_queue_label).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = PosterGray
                    )
                    Text(
                        text = pluralStringResource(
                            id = R.plurals.photo_count_selected,
                            count = photoCount,
                            photoCount
                        ),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (photoCount > 0) PosterOffWhite else PosterGray
                    )
                }

                PosterButton(
                    text = if (isProcessing) {
                        stringResource(R.string.action_renaming)
                    } else {
                        stringResource(R.string.action_rename_all)
                    },
                    enabled = isEnabled,
                    onClick = onRenameAllClick,
                    modifier = Modifier.weight(0.58f)
                )
            }
        }
    }
}

@Composable
fun VolleyballEmptyState() {
    val volleyballDesc = stringResource(R.string.cd_volleyball_icon)

    Surface(
        shape = JerseyCardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .size(84.dp)
                    .semantics { contentDescription = volleyballDesc }
            ) {
                val r = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val strokeWidth = 3.5.dp.toPx()

                drawCircle(
                    color = PosterOrangeRed,
                    radius = r * 0.92f,
                    center = center,
                    style = Stroke(width = strokeWidth)
                )

                drawArc(
                    color = PosterOrangeRed,
                    startAngle = -40f,
                    sweepAngle = 115f,
                    useCenter = false,
                    topLeft = Offset(center.x - r * 0.72f, center.y - r * 0.72f),
                    size = Size(r * 1.44f, r * 1.44f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                    color = PosterGray,
                    startAngle = 80f,
                    sweepAngle = 115f,
                    useCenter = false,
                    topLeft = Offset(center.x - r * 0.72f, center.y - r * 0.72f),
                    size = Size(r * 1.44f, r * 1.44f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                    color = PosterOrangeRed,
                    startAngle = 200f,
                    sweepAngle = 115f,
                    useCenter = false,
                    topLeft = Offset(center.x - r * 0.72f, center.y - r * 0.72f),
                    size = Size(r * 1.44f, r * 1.44f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawLine(
                    color = PosterGray,
                    start = Offset(center.x, center.y - r * 0.9f),
                    end = Offset(center.x, center.y),
                    strokeWidth = strokeWidth * 0.8f
                )
                drawLine(
                    color = PosterGray,
                    start = Offset(center.x, center.y),
                    end = Offset(center.x + r * 0.78f, center.y + r * 0.45f),
                    strokeWidth = strokeWidth * 0.8f
                )
                drawLine(
                    color = PosterGray,
                    start = Offset(center.x, center.y),
                    end = Offset(center.x - r * 0.78f, center.y + r * 0.45f),
                    strokeWidth = strokeWidth * 0.8f
                )
            }

            Text(
                text = stringResource(R.string.empty_state_message),
                style = MaterialTheme.typography.titleMedium,
                color = PosterGray,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun MatchDayResultCard(
    renamedCount: Int,
    savedResults: List<SaveResult>,
    onShareAllClick: () -> Unit,
    onStartOverClick: () -> Unit,
    onOpenSingleClick: (Uri) -> Unit
) {
    Surface(
        shape = JerseyCardShape,
        color = PosterInk,
        contentColor = PosterOffWhite,
        border = BorderStroke(2.dp, PosterGreen),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(JerseyThumbShape)
                        .background(PosterGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_success_check),
                        tint = PosterInk,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pluralStringResource(
                            id = R.plurals.photos_renamed_result,
                            count = renamedCount,
                            renamedCount
                        ).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.headlineMedium,
                        color = PosterGreen
                    )
                    Text(
                        text = "Pictures/${PhotoUtils.SUBFOLDER_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = PosterGray
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                savedResults.forEach { res ->
                    Surface(
                        shape = JerseyChipShape,
                        color = PosterCharcoal,
                        contentColor = PosterOffWhite,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(JerseyChipShape)
                            .clickable { onOpenSingleClick(res.savedUri) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "✔ ${res.finalFileName}",
                                style = MaterialTheme.typography.labelLarge,
                                color = PosterOffWhite
                            )
                            Text(
                                text = stringResource(R.string.action_open).uppercase(Locale.getDefault()),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = PosterGreen
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PosterButton(
                    text = stringResource(R.string.action_share),
                    icon = Icons.Default.Share,
                    containerColor = PosterGreen,
                    contentColor = PosterInk,
                    onClick = onShareAllClick,
                    modifier = Modifier.weight(1f)
                )

                PosterOutlinedButton(
                    text = stringResource(R.string.action_start_over),
                    icon = Icons.Default.Refresh,
                    onClick = onStartOverClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun PosterButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    containerColor: Color = PosterOrangeRed,
    contentColor: Color = PosterOffWhite
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.97f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "posterButtonScale"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = JerseyButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = PosterCharcoal,
            disabledContentColor = PosterGray
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        modifier = modifier
            .defaultMinSize(minHeight = 52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PosterOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "posterOutlinedButtonScale"
    )

    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = JerseyButtonShape,
        border = BorderStroke(1.5.dp, PosterGray),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = PosterOffWhite
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier
            .defaultMinSize(minHeight = 52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ClubDiamondLogo(bitmap: Bitmap?) {
    val desc = stringResource(R.string.cd_club_logo)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(JerseyThumbShape)
            .background(PosterOffWhite)
            .border(1.5.dp, PosterOrangeRed, JerseyThumbShape)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = desc,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val diamond = Path().apply {
                    moveTo(w / 2f, h * 0.06f)
                    lineTo(w * 0.94f, h / 2f)
                    lineTo(w / 2f, h * 0.94f)
                    lineTo(w * 0.06f, h / 2f)
                    close()
                }
                drawPath(diamond, color = PosterInk)
                drawRect(
                    color = PosterOrangeRed,
                    topLeft = Offset(w * 0.20f, h * 0.42f),
                    size = Size(w * 0.60f, h * 0.16f)
                )
            }
        }
    }
}

@Composable
fun SchoolHeaderBadge(
    bitmap: Bitmap?,
    onClick: () -> Unit
) {
    val desc = stringResource(R.string.cd_school_badge)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(PosterPureWhite)
            .border(2.dp, SchoolBadgeBlue, CircleShape)
            .clickable(
                role = Role.Button,
                onClickLabel = desc,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = desc,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Fit
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(color = SchoolBadgeBlue, radius = r)
                drawCircle(color = PosterPureWhite, radius = r * 0.72f)
                drawArc(
                    color = SchoolBadgePink,
                    startAngle = 195f,
                    sweepAngle = 150f,
                    useCenter = true,
                    topLeft = Offset(r * 0.45f, r * 0.65f),
                    size = Size(r * 1.1f, r * 0.9f)
                )
                drawCircle(
                    color = SchoolBadgeBlue,
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

@Composable
fun ConfettiExplosionOverlay(triggerKey: Int) {
    val progress = remember(triggerKey) { Animatable(0f) }
    val palette = remember {
        listOf(
            PosterOrangeRed,
            PosterGreen,
            PosterOffWhite,
            PosterWarningAmber,
            SchoolBadgeBlue
        )
    }

    val particles = remember(triggerKey) {
        val rng = Random(triggerKey * 997)
        List(85) {
            ConfettiPiece(
                xFraction = rng.nextFloat(),
                startY = -rng.nextFloat() * 550f,
                speedY = 950f + rng.nextFloat() * 1250f,
                driftX = (rng.nextFloat() - 0.5f) * 300f,
                sizePx = 14f + rng.nextFloat() * 14f,
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
            animationSpec = tween(durationMillis = 2800, easing = LinearEasing)
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
                val wave = sin(t * 10f + p.xFraction * 6f) * 26f
                val curX = (p.xFraction * width) + (p.driftX * t) + wave

                if (curY in -50f..height + 50f) {
                    rotate(
                        degrees = p.initialRotation + p.rotationSpeed * t,
                        pivot = Offset(curX, curY)
                    ) {
                        drawRect(
                            color = p.color.copy(alpha = alpha),
                            topLeft = Offset(curX - p.sizePx / 2f, curY - p.sizePx / 3f),
                            size = Size(p.sizePx, p.sizePx * 0.6f)
                        )
                    }
                }
            }
        }
    }
}

private fun openSavedImage(context: Context, uri: Uri) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.error_no_gallery_app), Toast.LENGTH_SHORT).show()
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
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.error_share_failed), Toast.LENGTH_SHORT).show()
    }
}